/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.logstreams.impl.log;

import static io.camunda.zeebe.logstreams.impl.serializer.DataFrameDescriptor.FRAME_ALIGNMENT;

import io.camunda.zeebe.logstreams.impl.serializer.DataFrameDescriptor;
import io.camunda.zeebe.logstreams.impl.serializer.SequencedBatchSerializer;
import io.camunda.zeebe.logstreams.log.LogAppendEntry;
import io.camunda.zeebe.logstreams.log.LogStreamWriter;
import io.camunda.zeebe.protocol.impl.record.RecordMetadata;
import io.camunda.zeebe.protocol.impl.record.value.management.AuditRecord;
import io.camunda.zeebe.protocol.record.RecordType;
import io.camunda.zeebe.protocol.record.ValueType;
import io.camunda.zeebe.protocol.record.intent.management.AuditIntent;
import io.camunda.zeebe.scheduler.ActorCondition;
import io.camunda.zeebe.scheduler.clock.ActorClock;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sequencer is a multiple-producer, single-consumer queue of {@link LogAppendEntry}. It buffers
 * a fixed amount of entries and rejects writes when the queue is full. The consumer may read at its
 * own pace by repeatedly calling {@link Sequencer#tryRead()} or register for notifications when new
 * entries are written by calling {@link Sequencer#registerConsumer(ActorCondition)}.
 *
 * <p>The sequencer assigns all entries a position and makes that position available to its
 * consumer. The sequencer does not copy or serialize entries, it only keeps a reference to them
 * until they are handed off to the consumer.
 */
final class Sequencer implements LogStreamWriter, Closeable {
  private static final Logger LOG = LoggerFactory.getLogger(Sequencer.class);
  private final int maxFragmentSize;

  private volatile long position;
  private volatile boolean isClosed = false;
  private volatile ActorCondition consumer;
  private final Queue<SequencedBatch> queue = new ArrayBlockingQueue<>(128);
  private final ReentrantLock lock = new ReentrantLock();
  private final SequencerMetrics metrics;

  Sequencer(final long initialPosition, final int maxFragmentSize, final SequencerMetrics metrics) {
    LOG.trace("Starting new sequencer at position {}", initialPosition);
    position = initialPosition;
    this.maxFragmentSize = maxFragmentSize;
    this.metrics = Objects.requireNonNull(metrics, "must specify metrics");
  }

  /** {@inheritDoc} */
  @Override
  public boolean canWriteEvents(final int eventCount, final int batchSize) {
    final int framedMessageLength =
        batchSize
            + eventCount * (DataFrameDescriptor.HEADER_LENGTH + FRAME_ALIGNMENT)
            + FRAME_ALIGNMENT;
    return framedMessageLength <= maxFragmentSize;
  }

  /** {@inheritDoc} */
  @Override
  public long tryWrite(final List<LogAppendEntry> appendEntries, final long sourcePosition) {
    if (isClosed) {
      LOG.warn("Rejecting write of {}, sequencer is closed", appendEntries);
      return -1;
    }

    for (final var entry : appendEntries) {
      if (!isEntryValid(entry)) {
        LOG.warn("Reject write of invalid entry {}", entry);
        return 0;
      }
    }
    final var batchSize = appendEntries.size();
    if (batchSize == 0) {
      return 0;
    }

    final long currentPosition;
    final boolean isEnqueued;
    lock.lock();
    try {
      currentPosition = position;
      final var sequencedBatch = reorganizeBatch(sourcePosition, appendEntries);
      isEnqueued = queue.offer(sequencedBatch);
      if (isEnqueued) {
        metrics.observeBatchLengthBytes(sequencedBatch.length());
        position = sequencedBatch.firstPosition() + sequencedBatch.entries().size();
      }
    } finally {
      lock.unlock();
    }

    if (consumer != null) {
      consumer.signal();
    }
    metrics.setQueueSize(queue.size());
    if (isEnqueued) {
      metrics.observeBatchSize(batchSize);
      return currentPosition + batchSize - 1;
    } else {
      LOG.trace("Rejecting write of {}, sequencer queue is full", appendEntries);
      return -1;
    }
  }

  /**
   * Organizes the given list of entries into:
   *
   * <ol>
   *   <li>{@link AuditRecord An audit record}
   *   <li>{@link io.camunda.zeebe.protocol.impl.record.value.management.AggregatedChangesRecord The
   *       writebatch}
   *   <li>Unprocessed Commands
   * </ol>
   */
  private SequencedBatch reorganizeBatch(
      final long sourcePosition, final List<LogAppendEntry> appendEntries) {
    final var now = ActorClock.currentTimeMillis();
    final List<LogAppendEntry> auditEntries = new ArrayList<>(appendEntries.size());
    final List<LogAppendEntry> unprocessedCommands = new ArrayList<>(1);
    LogAppendEntry writeBatchEntry = null;

    for (final var entry : appendEntries) {
      if (entry.recordMetadata().getValueType() == ValueType.AGGREGATED_CHANGES) {
        writeBatchEntry = entry;
      } else if (entry.recordMetadata().getRecordType() == RecordType.COMMAND
          && !entry.isProcessed()) {
        unprocessedCommands.add(entry);
      } else {
        auditEntries.add(entry);
      }
    }

    final var entriesToWrite = new ArrayList<LogAppendEntry>(unprocessedCommands.size() + 2);

    if (!auditEntries.isEmpty()) {
      final var auditEntry = buildAuditEntry(sourcePosition, now, auditEntries);
      entriesToWrite.add(auditEntry);
    }

    if (writeBatchEntry != null) {
      entriesToWrite.add(writeBatchEntry);
    }

    entriesToWrite.addAll(unprocessedCommands);
    return new SequencedBatch(now, position + auditEntries.size(), sourcePosition, entriesToWrite);
  }

  private LogAppendEntry buildAuditEntry(
      final long sourcePosition, final long now, final List<LogAppendEntry> auditEntries) {
    final var auditBatch = new SequencedBatch(now, position, sourcePosition, auditEntries);
    final var auditRecord = new AuditRecord();
    final var auditRecordMetadata =
        new RecordMetadata().recordType(RecordType.AUDIT).intent(AuditIntent.AUDITED);
    auditRecord.setEvents(new UnsafeBuffer(SequencedBatchSerializer.serializeBatch(auditBatch)));
    return LogAppendEntry.of(auditRecordMetadata, auditRecord);
  }

  /**
   * Retrieves, but does not remove, the first item in the sequenced batch queue.
   *
   * @return A {@link SequencedBatch} or null if none is available
   */
  SequencedBatch tryRead() {
    return queue.poll();
  }

  /**
   * Closes the sequencer. After closing, writes are rejected but reads are still allowed to drain
   * the queue. Closing the sequencer is not atomic so some writes may occur shortly after closing.
   */
  @Override
  public void close() {
    LOG.info("Closing sequencer for writing");
    isClosed = true;
  }

  void registerConsumer(final ActorCondition consumer) {
    this.consumer = consumer;
  }

  private boolean isEntryValid(final LogAppendEntry entry) {
    return entry.recordValue() != null
        && entry.recordValue().getLength() > 0
        && entry.recordMetadata() != null
        && entry.recordMetadata().getLength() > 0;
  }
}
