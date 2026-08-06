/*
 * Copyright 2026 the ratis-jepsen authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ratis.jepsen.kv;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.ratis.io.MD5Hash;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.util.LifeCycle;
import org.apache.ratis.util.MD5FileUtil;

import ratis.jepsen.kv.KvCodec.Reply;
import ratis.jepsen.kv.KvCodec.Request;

/**
 * The replicated key-value state machine (DESIGN 1.5), shaped after Ratis's
 * own {@code CounterStateMachine} at tag {@code ratis-3.2.2}.
 *
 * <p>{@link #applyTransaction} handles the write path (PUT/CAS) and runs
 * strictly serially on the single StateMachineUpdater thread;
 * {@link #query} handles the read path (GET) and may run concurrently with
 * it, hence the concurrent map. Linearizability of reads is enforced by the
 * server's ReadIndex path, not here.
 *
 * <p>Snapshots go through {@link SimpleStateMachineStorage}: a single file
 * {@code snapshot.<term>_<index>} holding the map as a {@link DataOutputStream}
 * stream — entry count, then (writeUTF key, writeLong value) pairs — plus the
 * {@code .md5} sidecar Ratis expects.
 *
 * <p>With {@link SeedBug#STALE_READS} active, reads are answered from a
 * shadow map to which each committed entry is applied only after
 * {@link #STALE_READS_DELAY_MILLIS}, deliberately violating linearizability
 * under concurrent writes (DESIGN 1.6).
 */
public class KvStateMachine extends BaseStateMachine {

  /** Shouting startup banner logged whenever a seeded bug is active. */
  public static final String SEED_BUG_BANNER_FORMAT = "*** SEEDED BUG ACTIVE: %s ***";

  /** How long the stale-reads shadow map lags committed writes. */
  public static final long STALE_READS_DELAY_MILLIS = 500;

  private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();

  /** The authoritative replicated state. */
  private final Map<String, Long> map = new ConcurrentHashMap<>();

  /** Serializes state+lastApplied mutation (apply, load) against snapshot capture. */
  private final Object applyLock = new Object();

  private final SeedBug seedBug;

  /** Lagging copy of {@link #map}; non-null only in stale-reads mode. */
  private final Map<String, Long> shadowMap;

  /** Single thread => shadow applies preserve the raft log's apply order. */
  private final ScheduledExecutorService shadowApplier;

  /** A state machine with no seeded bug. */
  public KvStateMachine() {
    this(null);
  }

  /**
   * A state machine with the given seeded bug, or none if {@code seedBug}
   * is null. This constructor is the single plumbing point for bug modes:
   * the CLI's {@code --seed-bug} argument maps here and tests use the same
   * path.
   */
  public KvStateMachine(SeedBug seedBug) {
    this.seedBug = seedBug;
    if (seedBug == SeedBug.STALE_READS) {
      this.shadowMap = new ConcurrentHashMap<>();
      this.shadowApplier = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "kv-stale-reads-shadow-applier");
        thread.setDaemon(true);
        return thread;
      });
    } else {
      this.shadowMap = null;
      this.shadowApplier = null;
    }
  }

  /** The active seeded bug, or null. */
  public SeedBug getSeedBug() {
    return seedBug;
  }

  @Override
  public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage)
      throws IOException {
    super.initialize(server, groupId, raftStorage);
    // Lifecycle discipline (Job 08 find): the live install-snapshot path
    // pauses the SM before replacing its storage
    // (ServerState.installSnapshot at ratis-3.2.2), and
    // StateMachineUpdater.reload() hard-asserts the PAUSED lifecycle state
    // — closing the whole division when the assert fails.
    // BaseStateMachine.pause() is an EMPTY method that never touches the
    // lifecycle, so without explicit management here every streamed
    // install killed the receiving division (observed live on Job 08's
    // combined membership+snapshot-churn runs; upstream's own
    // SimpleStateMachine4Testing does exactly this bookkeeping, which is
    // why upstream's install tests pass). RUNNING after initialize,
    // PAUSED in pause(), back to RUNNING after the post-install
    // reinitialize.
    getLifeCycle().startAndTransition(() -> {
      storage.init(raftStorage);
      if (seedBug != null) {
        LOG.warn(String.format(SEED_BUG_BANNER_FORMAT, seedBug.cliName()));
      }
      load(storage.loadLatestSnapshot());
    });
  }

  @Override
  public void pause() {
    getLifeCycle().transition(LifeCycle.State.PAUSING);
    getLifeCycle().transition(LifeCycle.State.PAUSED);
  }

  @Override
  public void reinitialize() throws IOException {
    load(storage.loadLatestSnapshot());
    if (getLifeCycleState() == LifeCycle.State.PAUSED) {
      getLifeCycle().transition(LifeCycle.State.STARTING);
      getLifeCycle().transition(LifeCycle.State.RUNNING);
    }
  }

  @Override
  public SimpleStateMachineStorage getStateMachineStorage() {
    return storage;
  }

  /**
   * Applies a committed PUT/CAS. Returns an already-completed future:
   * completing asynchronously would forfeit the ordering guarantees the
   * serial StateMachineUpdater thread provides.
   */
  @Override
  public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
    final LogEntryProto entry = trx.getLogEntry();
    final String command = entry.getStateMachineLogEntry().getLogData().toStringUtf8();
    final Request request = KvCodec.decodeRequest(command);

    final Reply reply;
    synchronized (applyLock) {
      reply = applyWrite(map, request);
      updateLastAppliedTermIndex(entry.getTerm(), entry.getIndex());
    }

    if (shadowApplier != null) {
      shadowApplier.schedule(() -> applyWrite(shadowMap, request),
          STALE_READS_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }
    return CompletableFuture.completedFuture(Message.valueOf(KvCodec.encodeReply(reply)));
  }

  /**
   * Answers a GET. In stale-reads mode the answer comes from the lagging
   * shadow map — that is the seeded bug.
   */
  @Override
  public CompletableFuture<Message> query(Message request) {
    final String command = request.getContent().toStringUtf8();
    final Request decoded = KvCodec.decodeRequest(command);
    final Map<String, Long> view = seedBug == SeedBug.STALE_READS ? shadowMap : map;
    final Reply reply = switch (decoded) {
      case Request.Get get -> {
        final Long value = view.get(get.key());
        yield value == null ? new Reply.Absent() : new Reply.Val(value);
      }
      case Request.Put put -> new Reply.Err("write command PUT sent via read path");
      case Request.Cas cas -> new Reply.Err("write command CAS sent via read path");
      case Request.Add add -> new Reply.Err("write command ADD sent via read path");
      case Request.Malformed malformed -> new Reply.Err(malformed.reason());
    };
    return CompletableFuture.completedFuture(Message.valueOf(KvCodec.encodeReply(reply)));
  }

  /**
   * Serializes the map into {@code snapshot.<term>_<index>} (with md5
   * sidecar) and registers it with the storage, mirroring
   * CounterStateMachine's takeSnapshot/saveSnapshot at ratis-3.2.2.
   *
   * @return the applied index the snapshot covers
   */
  @Override
  public long takeSnapshot() throws IOException {
    final TermIndex applied;
    final Map<String, Long> copy;
    synchronized (applyLock) {
      applied = getLastAppliedTermIndex();
      // TreeMap: deterministic on-disk entry order for a given state.
      copy = new TreeMap<>(map);
    }

    final File snapshotFile = storage.getSnapshotFile(applied.getTerm(), applied.getIndex());
    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
        Files.newOutputStream(snapshotFile.toPath())))) {
      out.writeInt(copy.size());
      for (Map.Entry<String, Long> e : copy.entrySet()) {
        out.writeUTF(e.getKey());
        out.writeLong(e.getValue());
      }
    } catch (IOException e) {
      throw new IOException("Failed to save snapshot at " + applied + " to " + snapshotFile, e);
    }

    final MD5Hash md5 = MD5FileUtil.computeAndSaveMd5ForFile(snapshotFile);
    final FileInfo info = new FileInfo(snapshotFile.toPath(), md5);
    storage.updateLatestSnapshot(new SingleFileSnapshotInfo(info, applied));

    LOG.info("{}: took snapshot {} covering {} ({} keys)",
        getId(), snapshotFile.getName(), applied, copy.size());
    return applied.getIndex();
  }

  /** Loads a snapshot, replacing in-memory state; no-op if none exists. */
  private void load(SingleFileSnapshotInfo snapshot) throws IOException {
    if (snapshot == null) {
      return;
    }
    final Path snapshotPath = snapshot.getFile().getPath();
    if (!Files.exists(snapshotPath)) {
      LOG.warn("{}: snapshot file {} does not exist for {}", getId(), snapshotPath, snapshot);
      return;
    }

    final MD5Hash md5 = snapshot.getFile().getFileDigest();
    if (md5 != null) {
      MD5FileUtil.verifySavedMD5(snapshotPath.toFile(), md5);
    }

    final TermIndex last = SimpleStateMachineStorage.getTermIndexFromSnapshotFile(snapshotPath.toFile());
    final Map<String, Long> loaded = new HashMap<>();
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(
        Files.newInputStream(snapshotPath)))) {
      final int size = in.readInt();
      for (int i = 0; i < size; i++) {
        final String key = in.readUTF();
        final long value = in.readLong();
        loaded.put(key, value);
      }
    }

    synchronized (applyLock) {
      map.clear();
      map.putAll(loaded);
      if (shadowMap != null) {
        shadowMap.clear();
        shadowMap.putAll(loaded);
      }
      setLastAppliedTermIndex(last);
    }
    LOG.info("{}: loaded snapshot {} covering {} ({} keys)",
        getId(), snapshotPath.getFileName(), last, loaded.size());
  }

  @Override
  public void close() throws IOException {
    if (shadowApplier != null) {
      shadowApplier.shutdownNow();
    }
    super.close();
  }

  /**
   * Applies a decoded request to a map, returning the protocol reply. Used
   * for both the authoritative map and the stale-reads shadow map: the same
   * request sequence in the same order yields the same state.
   */
  private static Reply applyWrite(Map<String, Long> target, Request request) {
    return switch (request) {
      case Request.Put put -> {
        target.put(put.key(), put.value());
        yield new Reply.Ok();
      }
      case Request.Cas cas -> {
        final Long current = target.get(cas.key());
        if (current == null) {
          yield new Reply.Absent();
        }
        if (current != cas.expect()) {
          yield new Reply.Mismatch(current);
        }
        target.put(cas.key(), cas.update());
        yield new Reply.Ok();
      }
      // The M3 increment: deliberately non-idempotent — a re-apply changes
      // state — which is exactly what makes it the retry-cache probe. The
      // reply carries the value AFTER this apply; on a deduplicated retry
      // the server returns the CACHED original reply instead of re-applying.
      case Request.Add add -> {
        final long updated = target.merge(add.key(), add.delta(), Long::sum);
        yield new Reply.Val(updated);
      }
      // A failed decode still reaches the log ("the log is the lock"); the
      // committed entry's reply is ERR rather than an exception (DESIGN 1.4).
      case Request.Malformed malformed -> new Reply.Err(malformed.reason());
      case Request.Get get -> new Reply.Err("read-only command GET sent via write path");
    };
  }
}
