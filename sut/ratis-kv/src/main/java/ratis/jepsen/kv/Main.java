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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.util.TimeDuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launcher for the ratis-kv server: a foreground process embedding one Ratis
 * {@link RaftServer} with the {@link KvStateMachine}, configured with the
 * production profile (DESIGN 1.3). Logs go to stdout; a failed start exits
 * non-zero.
 */
public final class Main {

  static {
    // slf4j-simple defaults to stderr and relative timestamps; the process
    // contract wants stdout, and the harness wants wall-clock times. Must be
    // set before the first LoggerFactory use; the launcher script sets the
    // same properties for defense in depth.
    setPropertyIfAbsent("org.slf4j.simpleLogger.logFile", "System.out");
    setPropertyIfAbsent("org.slf4j.simpleLogger.showDateTime", "true");
    setPropertyIfAbsent("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS");
  }

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  /**
   * The fixed raft group id compiled into the binary; identical on all peers
   * (a Ratis requirement — DESIGN 1.2).
   */
  public static final UUID GROUP_UUID = UUID.fromString("724d1912-848e-4e0f-a7e0-abbc16e54704");

  public static final RaftGroupId GROUP_ID = RaftGroupId.valueOf(GROUP_UUID);

  private Main() {
  }

  public static void main(String[] args) throws InterruptedException {
    if (Arrays.asList(args).contains("--help")) {
      System.out.println(usage());
      return;
    }

    final ServerOptions options;
    try {
      options = ServerOptions.parse(args);
    } catch (ServerOptions.UsageException e) {
      System.err.println("ratis-kv: " + e.getMessage());
      System.err.println(usage());
      System.exit(2);
      return;
    }

    final RaftServer server;
    try {
      server = buildServer(options);
      server.start();
    } catch (Throwable t) {
      // A failed start must be loud and non-zero, never wedged.
      LOG.error("ratis-kv {} failed to start", options.id(), t);
      System.exit(1);
      return;
    }

    if (options.seedBug() != null) {
      LOG.warn(String.format(KvStateMachine.SEED_BUG_BANNER_FORMAT, options.seedBug().cliName()));
    }
    if (options.join()) {
      // Emitted BEFORE the contract startup line so tooling that awaits the
      // startup line can also see the mode. The startup line itself is
      // byte-compatible with the DESIGN 2.6 contract; in join mode peers=
      // is the address book, not a formed conf.
      LOG.info("ratis-kv join mode: id={} formed no group; awaiting GroupManagementApi.add "
          + "(existing storage is recovered instead)", options.id());
    }
    LOG.info("ratis-kv server started: id={} address={} storage={} group={} peers={}",
        options.id(), options.selfAddress(), options.storageDir(), GROUP_ID, options.peers());

    final CountDownLatch terminated = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      LOG.info("ratis-kv server {} shutting down", options.id());
      try {
        server.close();
      } catch (IOException e) {
        LOG.warn("error closing RaftServer {}", options.id(), e);
      }
      terminated.countDown();
    }, "ratis-kv-shutdown"));
    terminated.await();
  }

  /**
   * Builds (but does not start) a RaftServer from parsed options: production
   * config profile, fixed group, {@link KvStateMachine} with the requested
   * seed-bug mode, {@code RECOVER} startup (works for both fresh and
   * existing storage dirs). This is the single assembly path — the CLI and
   * the in-JVM tests both use it.
   *
   * <p>In {@code --join} mode no group is set (the builder's group stays
   * null — the same shape as Ratis's own {@code GroupManagementBaseTest},
   * which starts servers "with null group"): on fresh storage the server
   * hosts nothing until the harness bootstraps it with
   * {@code GroupManagementApi.add}; on existing storage the proxy's startup
   * scan recovers the stored group ({@code RaftServerProxy.initGroups} at
   * ratis-3.2.2), which makes {@code --join} also the correct restart mode
   * for a node that already joined dynamically. {@code --peers} then serves
   * only as the address book (the self entry supplies the bind port).
   */
  public static RaftServer buildServer(ServerOptions options) throws IOException {
    final RaftServer.Builder builder = RaftServer.newBuilder()
        .setServerId(RaftPeerId.valueOf(options.id()))
        .setStateMachine(new KvStateMachine(options.seedBug()))
        .setProperties(buildProductionProperties(options))
        .setOption(RaftStorage.StartupOption.RECOVER);
    if (!options.join()) {
      builder.setGroup(buildGroup(options.peers()));
    }
    return builder.build();
  }

  /** The fixed-id group over the configured peers. */
  public static RaftGroup buildGroup(Map<String, String> peers) {
    final List<RaftPeer> raftPeers = new ArrayList<>(peers.size());
    for (Map.Entry<String, String> peer : peers.entrySet()) {
      raftPeers.add(RaftPeer.newBuilder().setId(peer.getKey()).setAddress(peer.getValue()).build());
    }
    return RaftGroup.valueOf(GROUP_ID, raftPeers);
  }

  /**
   * The production config profile (DESIGN 1.3): storage dir from
   * {@code --storage}, LINEARIZABLE reads, 1–2 s election timeouts,
   * auto-snapshot at 4096 entries, purge up to snapshot index, gRPC port
   * from this node's peer entry.
   */
  public static RaftProperties buildProductionProperties(ServerOptions options) {
    final RaftProperties properties = new RaftProperties();
    RaftServerConfigKeys.setStorageDir(properties, List.of(options.storageDir().toFile()));
    RaftServerConfigKeys.Read.setOption(properties, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
    RaftServerConfigKeys.Rpc.setTimeoutMin(properties, TimeDuration.valueOf(1, TimeUnit.SECONDS));
    RaftServerConfigKeys.Rpc.setTimeoutMax(properties, TimeDuration.valueOf(2, TimeUnit.SECONDS));
    RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(properties, true);
    RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(properties, 4096);
    RaftServerConfigKeys.Log.setPurgeUptoSnapshotIndex(properties, true);
    if (options.retryCacheExpiryMs() != null) {
      // M3/Q14 test lever ONLY — never part of the production profile: the
      // key's own contract ("set expiry time longer than total client retry
      // to guarantee exactly-once semantic", RaftServerConfigKeys.RetryCache
      // at 3.2.2) is exactly what the expiry-window run violates on purpose.
      RaftServerConfigKeys.RetryCache.setExpiryTime(properties,
          TimeDuration.valueOf(options.retryCacheExpiryMs(), TimeUnit.MILLISECONDS));
    }
    GrpcConfigKeys.Server.setPort(properties, options.selfPort());
    return properties;
  }

  static String usage() {
    return """
        Usage: ratis-kv --id <id> --peers <id=host:port>[,<id=host:port>...] \\
                        --storage <dir> [--join] [--retry-cache-expiry-ms <ms>] \\
                        [--seed-bug stale-reads] [--help]

          --id       this node's id; must appear in --peers
          --peers    the full fixed voter set, identical on every node
                     (in --join mode: an address book — only the self entry
                     is used, for the bind port)
          --storage  raft storage directory
          --join     start without forming a group: the server hosts nothing
                     until it is bootstrapped via GroupManagementApi.add and
                     committed into the conf by setConfiguration (existing
                     storage, if any, is recovered instead) — the M2
                     membership-pool mode
          --retry-cache-expiry-ms
                     override raft.server.retrycache.expirytime (absent:
                     Ratis default 60 s). M3/Q14 test lever only — shrinking
                     it below the client's total retry span deliberately
                     re-arms the documented retry double-apply boundary
          --seed-bug activate a deliberately seeded bug (testing the test
                     harness only; never use in a run you care about):
                       stale-reads  linearizable reads observe ~500 ms stale state
          --help     print this help and exit 0

        The raft group id is the compiled-in constant %s.
        Runs in the foreground and logs to stdout; exits non-zero on fatal
        startup errors.""".formatted(GROUP_UUID);
  }

  private static void setPropertyIfAbsent(String key, String value) {
    if (System.getProperty(key) == null) {
      System.setProperty(key, value);
    }
  }
}
