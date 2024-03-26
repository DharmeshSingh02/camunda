/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.streamprocessor;

import io.camunda.zeebe.engine.Loggers;
import io.camunda.zeebe.engine.api.SimpleProcessingScheduleService;
import io.camunda.zeebe.engine.api.Task;
import io.camunda.zeebe.logstreams.log.LogStreamBatchWriter;
import io.camunda.zeebe.scheduler.ActorControl;
import io.camunda.zeebe.scheduler.future.ActorFuture;
import io.camunda.zeebe.scheduler.future.CompletableActorFuture;
import io.camunda.zeebe.scheduler.retry.AbortableRetryStrategy;
import io.camunda.zeebe.streamprocessor.ScheduledCommandCache.StageableScheduledCommandCache;
import io.camunda.zeebe.streamprocessor.StreamProcessor.Phase;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * Here the implementation is just a suggestion to amke the engine abstraction work. Can be whatever
 * PDT team thinks is best to work with
 */
public class ProcessingScheduleServiceImpl
    implements SimpleProcessingScheduleService, AutoCloseable {

  private static final ScheduledTask NOOP_SCHEDULED_TASK = () -> {};
  private static final Logger LOG = Loggers.STREAM_PROCESSING;
  private final Supplier<StreamProcessor.Phase> streamProcessorPhaseSupplier;
  private final BooleanSupplier abortCondition;
  private final Supplier<ActorFuture<LogStreamBatchWriter>> writerAsyncSupplier;
  private final StageableScheduledCommandCache commandCache;
  private LogStreamBatchWriter logStreamBatchWriter;
  private ActorControl actorControl;
  private AbortableRetryStrategy writeRetryStrategy;
  private CompletableActorFuture<Void> openFuture;

  public ProcessingScheduleServiceImpl(
      final Supplier<Phase> streamProcessorPhaseSupplier,
      final BooleanSupplier abortCondition,
      final Supplier<ActorFuture<LogStreamBatchWriter>> writerAsyncSupplier,
      final StageableScheduledCommandCache commandCache) {
    this.streamProcessorPhaseSupplier = streamProcessorPhaseSupplier;
    this.abortCondition = abortCondition;
    this.writerAsyncSupplier = writerAsyncSupplier;
    this.commandCache = commandCache;
  }

  @Override
  public ScheduledTask runDelayed(final Duration delay, final Runnable followUpTask) {
    if (actorControl == null) {
      LOG.debug("ProcessingScheduleService hasn't been opened yet, ignore scheduled task.");
      return NOOP_SCHEDULED_TASK;
    }
    final var scheduledTimer = actorControl.runDelayed(delay, followUpTask);
    return scheduledTimer::cancel;
  }

  @Override
  public ScheduledTask runDelayed(final Duration delay, final Task task) {
    return runDelayed(delay, toRunnable(task));
  }

  @Override
  public void runAtFixedRate(final Duration delay, final Task task) {
    runDelayed(
        delay,
        toRunnable(
            builder -> {
              try {
                return task.execute(builder);
              } finally {
                runAtFixedRate(delay, task);
              }
            }));
  }

  public ActorFuture<Void> open(final ActorControl control) {
    if (openFuture != null) {
      return openFuture;
    }

    openFuture = new CompletableActorFuture<>();
    writeRetryStrategy = new AbortableRetryStrategy(control);
    final var writerFuture = writerAsyncSupplier.get();
    control.runOnCompletion(
        writerFuture,
        (writer, failure) -> {
          if (failure == null) {
            logStreamBatchWriter = writer;
            actorControl = control;
            openFuture.complete(null);
          } else {
            openFuture.completeExceptionally(failure);
          }
        });
    return openFuture;
  }

  @Override
  public void close() {
    actorControl = null;
    logStreamBatchWriter = null;
    writeRetryStrategy = null;
    openFuture = null;
  }

  Runnable toRunnable(final Task task) {
    return () -> {
      if (abortCondition.getAsBoolean()) {
        // it might be that we are closing, then we should just stop
        return;
      }

      final var currentStreamProcessorPhase = streamProcessorPhaseSupplier.get();
      if (currentStreamProcessorPhase != Phase.PROCESSING) {

        // We want to execute the scheduled tasks only if the StreamProcessor is in the PROCESSING
        // PHASE
        //
        // To make sure that:
        //
        //  * we are not running during replay/init phase (the state might not be up-to-date yet)
        //  * we are not running during suspending
        //
        LOG.trace(
            "Not able to execute scheduled task right now. [streamProcessorPhase: {}]",
            currentStreamProcessorPhase);
        actorControl.submit(toRunnable(task));
        return;
      }
      final var builder =
          new BufferedTaskResultBuilder(logStreamBatchWriter::canWriteAdditionalEvent);
      final var result = task.execute(builder);
      final var stagedCache = commandCache.stage();

      logStreamBatchWriter.reset();
      result
          .getRecordBatch()
          .forEach(
              entry -> {
                final var intent = entry.recordMetadata().getIntent();
                final var key = entry.key();
                if (stagedCache.contains(intent, key)) {
                  return;
                }

                logStreamBatchWriter
                    .event()
                    .key(entry.key())
                    .metadataWriter(entry.recordMetadata())
                    .sourceIndex(entry.sourceIndex())
                    .valueWriter(entry.recordValue())
                    .done();
                stagedCache.add(intent, entry.key());
              });

      // we need to retry the writing if the dispatcher return zero or negative position (this means
      // it was full during writing)
      // it will be freed from the LogStorageAppender concurrently, which means we might be able to
      // write later
      final var writeFuture =
          writeRetryStrategy.runWithRetry(
              () -> {
                LOG.trace("Write scheduled TaskResult to dispatcher!");
                return logStreamBatchWriter.tryWrite() >= 0;
              },
              abortCondition);

      writeFuture.onComplete(
          (v, t) -> {
            if (t == null) {
              stagedCache.persist();
            } else {
              // todo handle error;
              //   can happen if we tried to write a too big batch of records
              //   this should resolve if we use the buffered writer were we detect these errors
              // earlier
              LOG.warn("Writing of scheduled TaskResult failed!", t);
            }
          });
    };
  }
}
