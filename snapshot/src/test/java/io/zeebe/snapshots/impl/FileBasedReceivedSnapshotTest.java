/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.snapshots.impl;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.snapshots.PersistedSnapshot;
import io.zeebe.snapshots.PersistedSnapshotListener;
import io.zeebe.snapshots.ReceivedSnapshot;
import io.zeebe.snapshots.SnapshotChunk;
import io.zeebe.util.FileUtil;
import io.zeebe.util.sched.testing.ActorSchedulerRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.agrona.IoUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileBasedReceivedSnapshotTest {
  private static final String SNAPSHOT_DIRECTORY = "snapshots";
  private static final String PENDING_DIRECTORY = "pending";
  private static final int PARTITION_ID = 1;
  private static final Map<String, String> SNAPSHOT_FILE_CONTENTS =
      Map.of(
          "file1", "file1 contents",
          "file2", "file2 contents");

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
  @Rule public ActorSchedulerRule scheduler = new ActorSchedulerRule();

  private FileBasedSnapshotStore senderSnapshotStore;
  private FileBasedSnapshotStore receiverSnapshotStore;
  private Path receiverPendingDir;
  private Path receiverSnapshotsDir;

  @Before
  public void beforeEach() throws Exception {
    final var receiverRoot = temporaryFolder.newFolder("receiver").toPath();
    receiverPendingDir = receiverRoot.resolve(PENDING_DIRECTORY);
    receiverSnapshotsDir = receiverRoot.resolve(SNAPSHOT_DIRECTORY);
    receiverSnapshotStore = createStore(2, receiverSnapshotsDir, receiverPendingDir);

    final var senderRoot = temporaryFolder.newFolder("sender").toPath();
    senderSnapshotStore =
        createStore(
            1, senderRoot.resolve(SNAPSHOT_DIRECTORY), senderRoot.resolve(PENDING_DIRECTORY));
  }

  @Test
  public void shouldNotCreatePendingDirectoryUntilFirstChunk() {
    // given

    // when
    final ReceivedSnapshot receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot("1-0-123-121");

    // then
    assertThat(receivedSnapshot.getPath())
        .as("there is no pending snapshots until a chunk is applied")
        .doesNotExist();
  }

  @Test
  public void shouldStoreReceivedSnapshotInPendingDirectory() throws IOException {
    // given
    final var persistedSnapshot = takePersistedSnapshot(1L);

    // when
    final var receivedSnapshot = receiveSnapshot(persistedSnapshot);

    // then
    assertThat(receivedSnapshot.getPath())
        .as("there exists a pending snapshot in the pending directory")
        .hasParent(receiverPendingDir)
        .isDirectory();
    assertThat(receiverSnapshotsDir)
        .as("the pending snapshot was not committed")
        .isEmptyDirectory();
  }

  @Test
  public void shouldReceiveChunk() throws Exception {
    // given
    final var persistedSnapshot = takePersistedSnapshot(1L);
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());

    // when
    final SnapshotChunk expectedChunk;
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      expectedChunk = snapshotChunkReader.next();
      receivedSnapshot.apply(expectedChunk).join();
    }

    // then
    assertThat(receivedSnapshot.getPath())
        .as("the received snapshot directory contains the applied chunk")
        .isDirectoryContaining(
            p -> p.getFileName().toString().equals(expectedChunk.getChunkName()));
    assertThat(receivedSnapshot.getPath().resolve(expectedChunk.getChunkName()))
        .as("the received chunk should have the expected content")
        .hasBinaryContent(expectedChunk.getContent());
  }

  @Test
  public void shouldRemovePreviousSnapshotOnCommit() throws Exception {
    // given
    final PersistedSnapshot firstPersistedSnapshot = takePersistedSnapshot(1L);
    receiveSnapshot(firstPersistedSnapshot).persist().join();

    // when
    final PersistedSnapshot secondPersistedSnapshot = takePersistedSnapshot(2L);
    final var secondReceivedPersistedSnapshot =
        receiveSnapshot(secondPersistedSnapshot).persist().join();

    // then
    assertThat(receiverSnapshotsDir)
        .as("there is only the latest snapshot in the receiver's snapshot directory")
        .isDirectoryNotContaining(p -> !p.equals(secondReceivedPersistedSnapshot.getPath()));
  }

  @Test
  public void shouldNotRemovePendingSnapshotOnCommittingSnapshotWhenHigher() throws Exception {
    // given
    final var olderPersistedSnapshot = takePersistedSnapshot(1L);
    final ReceivedSnapshot olderReceivedSnapshot = receiveSnapshot(olderPersistedSnapshot);
    final var newPersistedSnapshot = takePersistedSnapshot(2L);

    // when
    final ReceivedSnapshot receivedSnapshot = receiveSnapshot(newPersistedSnapshot);
    olderReceivedSnapshot.persist().join();

    // then
    assertThat(receiverPendingDir)
        .as(
            "the latest pending snapshot should not be deleted because it is newer than the persisted one")
        .isDirectoryContaining(p -> p.equals(receivedSnapshot.getPath()))
        .isDirectoryNotContaining(p -> !p.equals(receivedSnapshot.getPath()));
  }

  @Test
  public void shouldNotifyListenersOnNewSnapshot() throws Exception {
    // given
    final AtomicReference<PersistedSnapshot> snapshotRef = new AtomicReference<>();
    final PersistedSnapshotListener listener = snapshotRef::set;
    receiverSnapshotStore.addSnapshotListener(listener);

    // when
    final PersistedSnapshot persistedSnapshot1 = takePersistedSnapshot(1L);
    final var persistedSnapshot = receiveSnapshot(persistedSnapshot1).persist().join();

    // then
    assertThat(snapshotRef)
        .as("the listener was called with the correct new snapshot reference")
        .hasValue(persistedSnapshot);
  }

  @Test
  public void shouldNotNotifyListenersOnNewSnapshotWhenRemoved() throws Exception {
    // given
    final AtomicReference<PersistedSnapshot> snapshotRef = new AtomicReference<>();
    final PersistedSnapshotListener listener = snapshotRef::set;
    senderSnapshotStore.addSnapshotListener(listener);

    // when
    senderSnapshotStore.removeSnapshotListener(listener);
    final PersistedSnapshot persistedSnapshot = takePersistedSnapshot(1L);
    receiveSnapshot(persistedSnapshot).persist().join();

    // then
    assertThat(snapshotRef)
        .as("the listener was never called and the ref value is still null")
        .hasValue(null);
  }

  @Test
  public void shouldNotWriteChunkIfItHasTheWrongSnapshotChecksum() throws Exception {
    // given
    final var persistedSnapshot = takePersistedSnapshot(1L);

    // when
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());

    final SnapshotChunk firstChunk;
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      firstChunk = snapshotChunkReader.next();
      receivedSnapshot.apply(firstChunk).join();

      final SnapshotChunk corruptedChunk =
          SnapshotChunkWrapper.withSnapshotChecksum(firstChunk, 0xCAFEL);
      receivedSnapshot.apply(corruptedChunk).join();
    }

    assertThat(receivedSnapshot.getPath())
        .as("the received snapshot should contain only the first chunk")
        .isDirectoryNotContaining(
            p -> !p.getFileName().toString().equals(firstChunk.getChunkName()));
  }

  @Test
  public void shouldNotWriteChunkWithInvalidChunkChecksum() throws Exception {
    // given
    final var persistedSnapshot = takePersistedSnapshot(1L);

    // when
    final SnapshotChunk firstChunk;
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      firstChunk = snapshotChunkReader.next();
      receivedSnapshot.apply(firstChunk).join();

      final var corruptedChunk = SnapshotChunkWrapper.withChecksum(firstChunk, 0xCAFEL);
      receivedSnapshot.apply(corruptedChunk).join();
    }

    // then
    assertThat(receivedSnapshot.getPath())
        .as("the received snapshot should contain only the first chunk")
        .isDirectoryNotContaining(
            p -> !p.getFileName().toString().equals(firstChunk.getChunkName()));
  }

  @Test
  public void shouldNotWriteChunkWithWrongTotalChunkCount() throws Exception {
    // given
    final var persistedSnapshot = takePersistedSnapshot(1L);

    // when
    final SnapshotChunk firstChunk;
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      firstChunk = snapshotChunkReader.next();
      receivedSnapshot.apply(firstChunk).join();

      final var corruptedChunk =
          SnapshotChunkWrapper.withTotalCount(snapshotChunkReader.next(), 55);
      receivedSnapshot.apply(corruptedChunk).join();
    }

    // then
    assertThat(receivedSnapshot.getPath())
        .as("the received snapshot should contain only the first chunk")
        .isDirectoryNotContaining(
            p -> !p.getFileName().toString().equals(firstChunk.getChunkName()));
  }

  @Test
  public void shouldNotWriteChunkOnInvalidId() throws Exception {
    // given
    final var persistedSnapshot = takePersistedSnapshot(1L);

    // when
    final SnapshotChunk firstChunk;
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());
    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      firstChunk = snapshotChunkReader.next();
      receivedSnapshot.apply(firstChunk).join();

      final SnapshotChunk corruptedChunk =
          SnapshotChunkWrapper.withSnapshotId(snapshotChunkReader.next(), "id");
      receivedSnapshot.apply(corruptedChunk).join();
    }

    // then
    assertThat(receivedSnapshot.getPath())
        .as("the received snapshot should contain only the first chunk")
        .isDirectoryNotContaining(
            p -> !p.getFileName().toString().equals(firstChunk.getChunkName()));
  }

  private ReceivedSnapshot receiveSnapshot(final PersistedSnapshot persistedSnapshot)
      throws IOException {
    final var receivedSnapshot =
        receiverSnapshotStore.newReceivedSnapshot(persistedSnapshot.getId());

    try (final var snapshotChunkReader = persistedSnapshot.newChunkReader()) {
      while (snapshotChunkReader.hasNext()) {
        receivedSnapshot.apply(snapshotChunkReader.next()).join();
      }
    }

    return receivedSnapshot;
  }

  private PersistedSnapshot takePersistedSnapshot(final long index) {
    final var transientSnapshot =
        senderSnapshotStore.newTransientSnapshot(index, 0L, 1, 0).orElseThrow();
    transientSnapshot.take(this::writeSnapshot).join();
    return transientSnapshot.persist().join();
  }

  private boolean writeSnapshot(final Path path) {
    try {
      FileUtil.ensureDirectoryExists(path);

      for (final var entry : SNAPSHOT_FILE_CONTENTS.entrySet()) {
        final var fileName = path.resolve(entry.getKey());
        final var fileContent = entry.getValue().getBytes(StandardCharsets.UTF_8);
        Files.write(fileName, fileContent, CREATE_NEW, StandardOpenOption.WRITE);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return true;
  }

  private FileBasedSnapshotStore createStore(
      final int nodeId, final Path snapshotDir, final Path pendingDir) {
    final var store =
        new FileBasedSnapshotStore(
            nodeId,
            PARTITION_ID,
            new SnapshotMetrics(nodeId + "-" + PARTITION_ID),
            snapshotDir,
            pendingDir);

    IoUtil.ensureDirectoryExists(snapshotDir.toFile(), "snapshots directory");
    IoUtil.ensureDirectoryExists(pendingDir.toFile(), "pending directory");
    scheduler.submitActor(store);

    return store;
  }
}
