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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.util.TimeDuration;

/**
 * An in-JVM 3-plus-node ratis-kv cluster on 127.0.0.1 ephemeral ports with
 * per-node temp storage dirs — standard Ratis test practice (its own suite
 * runs multi-server in one JVM).
 *
 * <p>Servers are assembled through the very same code path the CLI uses:
 * an argv array is built and run through {@link ServerOptions#parse}, then
 * {@link Main#buildServer} — so the production config profile and the
 * {@code --seed-bug} plumbing are exactly what a real deployment gets.
 */
final class MiniCluster implements AutoCloseable {

  static final Pattern SNAPSHOT_FILE = Pattern.compile("snapshot\\.\\d+_\\d+");

  private final Path baseDir;
  private final Map<String, ServerOptions> optionsById = new LinkedHashMap<>();
  private final Map<String, RaftServer> serversById = new LinkedHashMap<>();

  private MiniCluster(Path baseDir) {
    this.baseDir = baseDir;
  }

  /** Starts {@code n} servers; {@code seedBug} may be null for a correct cluster. */
  static MiniCluster start(Path baseDir, int n, SeedBug seedBug) throws Exception {
    return start(baseDir, n, 0, seedBug);
  }

  /**
   * Starts {@code voters} servers forming the group plus {@code joiners}
   * servers in {@code --join} mode (in the initial conf's address book but
   * NOT its group — the M2 membership-pool posture). Joiner ids continue
   * the voter numbering: 3 voters + 1 joiner = n1..n3 voting, n4 joining.
   * Voters get exactly the voter set as {@code --peers} (the initial conf);
   * joiners get the full address book plus {@code --join}.
   */
  static MiniCluster start(Path baseDir, int voters, int joiners, SeedBug seedBug)
      throws Exception {
    final MiniCluster cluster = new MiniCluster(baseDir);
    final int n = voters + joiners;
    final List<Integer> ports = allocatePorts(n);

    final StringBuilder votersSpec = new StringBuilder();
    final StringBuilder fullSpec = new StringBuilder();
    for (int i = 0; i < n; i++) {
      final String entry = "n" + (i + 1) + "=127.0.0.1:" + ports.get(i);
      if (i < voters) {
        if (i > 0) {
          votersSpec.append(',');
        }
        votersSpec.append(entry);
      }
      if (i > 0) {
        fullSpec.append(',');
      }
      fullSpec.append(entry);
    }

    for (int i = 0; i < n; i++) {
      final String id = "n" + (i + 1);
      final boolean join = i >= voters;
      final List<String> argv = new ArrayList<>(List.of(
          "--id", id,
          "--peers", join ? fullSpec.toString() : votersSpec.toString(),
          "--storage", baseDir.resolve(id).toString()));
      if (join) {
        argv.add("--join");
      }
      if (seedBug != null) {
        argv.add("--seed-bug");
        argv.add(seedBug.cliName());
      }
      cluster.optionsById.put(id, ServerOptions.parse(argv.toArray(new String[0])));
    }

    cluster.startAll();
    return cluster;
  }

  private void startAll() throws IOException {
    for (Map.Entry<String, ServerOptions> e : optionsById.entrySet()) {
      final RaftServer server = Main.buildServer(e.getValue());
      server.start();
      serversById.put(e.getKey(), server);
    }
  }

  /** Closes every server, then rebuilds and restarts them (RECOVER) on the same dirs. */
  void restartAll() throws IOException {
    closeAll();
    startAll();
  }

  /** A client for the cluster's group with a bounded retry policy (~30 s). */
  RaftClient newClient() {
    final ServerOptions any = optionsById.values().iterator().next();
    return newClient(any.peers());
  }

  /**
   * A client over every node (voters and join-mode members alike) — the
   * peer list a membership test needs so GroupManagementApi calls can be
   * routed to a joiner by id.
   */
  RaftClient newClientAllNodes() {
    final Map<String, String> all = new LinkedHashMap<>();
    for (ServerOptions options : optionsById.values()) {
      all.put(options.id(), options.selfAddress());
    }
    return newClient(all);
  }

  private RaftClient newClient(Map<String, String> peers) {
    return RaftClient.newBuilder()
        .setProperties(new RaftProperties())
        .setRaftGroup(Main.buildGroup(peers))
        .setRetryPolicy(RetryPolicies.retryUpToMaximumCountWithFixedSleep(
            150, TimeDuration.valueOf(200, TimeUnit.MILLISECONDS)))
        .build();
  }

  /** The parsed options of node {@code id} (ports, storage, peers). */
  ServerOptions options(String id) {
    return optionsById.get(id);
  }

  /** The running RaftServer of node {@code id}. */
  RaftServer server(String id) {
    return serversById.get(id);
  }

  // ---- protocol helpers ----

  static String send(RaftClient client, String request) throws IOException {
    return replyText(client.io().send(Message.valueOf(request)));
  }

  static String sendReadOnly(RaftClient client, String request) throws IOException {
    return replyText(client.io().sendReadOnly(Message.valueOf(request)));
  }

  private static String replyText(RaftClientReply reply) {
    return reply.getMessage().getContent().toStringUtf8();
  }

  /**
   * Sends a write, retrying through the transient failures of a cluster
   * that has not elected a leader yet (first contact after boot/restart).
   */
  static String sendUntilSuccess(RaftClient client, String request, Duration deadline)
      throws Exception {
    return untilSuccess(() -> send(client, request), deadline);
  }

  /** Read-path twin of {@link #sendUntilSuccess}. */
  static String sendReadOnlyUntilSuccess(RaftClient client, String request, Duration deadline)
      throws Exception {
    return untilSuccess(() -> sendReadOnly(client, request), deadline);
  }

  private static String untilSuccess(Attempt attempt, Duration deadline) throws Exception {
    final Instant end = Instant.now().plus(deadline);
    IOException last = null;
    while (Instant.now().isBefore(end)) {
      try {
        return attempt.get();
      } catch (IOException e) {
        last = e;
        Thread.sleep(250);
      }
    }
    throw new AssertionError("no successful reply within " + deadline, last);
  }

  @FunctionalInterface
  private interface Attempt {
    String get() throws IOException;
  }

  /** Waits until some server's storage contains a snapshot.<term>_<index> file. */
  Path awaitSnapshotFile(Duration deadline) throws Exception {
    final Instant end = Instant.now().plus(deadline);
    while (true) {
      try (Stream<Path> walk = Files.walk(baseDir)) {
        final Path found = walk
            .filter(p -> SNAPSHOT_FILE.matcher(p.getFileName().toString()).matches())
            .findFirst().orElse(null);
        if (found != null) {
          return found;
        }
      }
      if (!Instant.now().isBefore(end)) {
        throw new AssertionError("no snapshot file appeared under " + baseDir
            + " within " + deadline);
      }
      Thread.sleep(250);
    }
  }

  private void closeAll() {
    for (RaftServer server : serversById.values()) {
      try {
        server.close();
      } catch (IOException e) {
        // best-effort teardown
      }
    }
    serversById.clear();
  }

  @Override
  public void close() {
    closeAll();
  }

  private static List<Integer> allocatePorts(int n) throws IOException {
    // Open all sockets simultaneously so the kernel hands out n distinct
    // ephemeral ports, then release them for the servers to bind.
    final List<ServerSocket> sockets = new ArrayList<>(n);
    final List<Integer> ports = new ArrayList<>(n);
    try {
      for (int i = 0; i < n; i++) {
        final ServerSocket socket = new ServerSocket(0);
        socket.setReuseAddress(true);
        sockets.add(socket);
        ports.add(socket.getLocalPort());
      }
    } finally {
      for (ServerSocket socket : sockets) {
        socket.close();
      }
    }
    return ports;
  }
}
