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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.GroupInfoReply;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.SetConfigurationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code --join} mode smoke test (Job 08): a joiner starts group-less
 * and is brought in by the harness-side flow the membership nemesis uses —
 * {@code GroupManagementApi.add} with the empty-peers group, then
 * {@code AdminApi.setConfiguration} with the {@code Arguments} builder in
 * {@code COMPARE_AND_SET} mode. (The builder is deliberate: the
 * {@code (RaftPeer[], RaftPeer[])} convenience overload is broken at 3.2.2 —
 * RATIS-2640 — and must not be used.)
 */
class JoinModeTest {

  /** Pure CLI-contract coverage of the new flag. */
  @Test
  void parsesJoinFlag() throws Exception {
    final ServerOptions join = ServerOptions.parse(new String[] {
        "--id", "n4", "--peers", "n1=h1:6000,n4=h4:6000", "--storage", "/tmp/s", "--join"});
    assertTrue(join.join());
    assertEquals("h4:6000", join.selfAddress());

    final ServerOptions plain = ServerOptions.parse(new String[] {
        "--id", "n1", "--peers", "n1=h1:6000", "--storage", "/tmp/s"});
    assertFalse(plain.join());

    // --join does not relax the id-must-appear-in-peers rule: the self
    // entry is still where the bind port comes from.
    assertThrows(ServerOptions.UsageException.class, () -> ServerOptions.parse(new String[] {
        "--id", "n9", "--peers", "n1=h1:6000", "--storage", "/tmp/s", "--join"}));
  }

  @Test
  @Timeout(300)
  void joinAddCommitServeRemove(@TempDir Path tmp) throws Exception {
    try (MiniCluster cluster = MiniCluster.start(tmp, 3, 1, null);
         RaftClient client = cluster.newClientAllNodes()) {
      final RaftPeerId joiner = RaftPeerId.valueOf("n4");

      // (a) The joiner starts hosting no group at all.
      assertTrue(client.getGroupManagementApi(joiner).list().getGroupIds().isEmpty(),
          "join-mode server should host no group before bootstrap");

      // First op rides out leader election.
      assertEquals("OK", MiniCluster.sendUntilSuccess(client, "PUT k 1", Duration.ofSeconds(60)));

      // (b) Bootstrap: create the (empty-conf) division on the joiner. The
      // empty peer list is the upstream reconfiguration-test shape — the
      // division cannot start elections until the leader commits it in.
      assertTrue(client.getGroupManagementApi(joiner)
          .add(RaftGroup.valueOf(Main.GROUP_ID)).isSuccess());

      // (c) Commit it into the conf: COMPARE_AND_SET against the current
      // 3-voter conf, servers-in-new-conf = the four of them.
      final List<RaftPeer> voters = peers(cluster, "n1", "n2", "n3");
      final List<RaftPeer> withJoiner = peers(cluster, "n1", "n2", "n3", "n4");
      assertTrue(client.admin().setConfiguration(SetConfigurationRequest.Arguments.newBuilder()
          .setServersInCurrentConf(voters)
          .setServersInNewConf(withJoiner)
          .setMode(SetConfigurationRequest.Mode.COMPARE_AND_SET)
          .build()).isSuccess());
      awaitConfServers(client, Set.of("n1", "n2", "n3", "n4"), Duration.ofSeconds(60));

      // (d) The joined node serves: write, then a linearizable read
      // targeted at the joiner itself.
      assertEquals("OK", MiniCluster.sendUntilSuccess(client, "PUT k 2", Duration.ofSeconds(30)));
      assertEquals("VAL 2", untilSuccess(
          () -> client.io().sendReadOnly(Message.valueOf("GET k"), joiner)
              .getMessage().getContent().toStringUtf8(),
          Duration.ofSeconds(30)));

      // (e) And back out: COMPARE_AND_SET down to the original three.
      assertTrue(client.admin().setConfiguration(SetConfigurationRequest.Arguments.newBuilder()
          .setServersInCurrentConf(withJoiner)
          .setServersInNewConf(voters)
          .setMode(SetConfigurationRequest.Mode.COMPARE_AND_SET)
          .build()).isSuccess());
      awaitConfServers(client, Set.of("n1", "n2", "n3"), Duration.ofSeconds(60));

      assertEquals("VAL 2", MiniCluster.sendReadOnlyUntilSuccess(
          client, "GET k", Duration.ofSeconds(30)));
    }
  }

  private static List<RaftPeer> peers(MiniCluster cluster, String... ids) {
    return List.of(ids).stream()
        .map(id -> RaftPeer.newBuilder()
            .setId(id)
            .setAddress(cluster.options(id).selfAddress())
            .build())
        .collect(Collectors.toList());
  }

  /**
   * Polls GroupInfo (from any voter that answers) until the conf's member
   * set equals {@code expected}. Membership comes from the reply's group —
   * {@code Division.getGroup()} is built from the current conf — because
   * the reply's dedicated conf field is dropped by the 3.2.2 wire
   * serializer ({@code toGroupInfoReplyProto} never sets it), so a remote
   * client always sees it empty. During a transitional (old,new) conf the
   * group is the union of both, which cannot equal {@code expected}; the
   * poll therefore only completes on the stable conf.
   */
  private static void awaitConfServers(RaftClient client, Set<String> expected, Duration deadline)
      throws Exception {
    final Instant end = Instant.now().plus(deadline);
    Set<String> last = null;
    while (Instant.now().isBefore(end)) {
      for (String node : List.of("n1", "n2", "n3")) {
        try {
          final GroupInfoReply reply =
              client.getGroupManagementApi(RaftPeerId.valueOf(node)).info(Main.GROUP_ID);
          final Set<String> members = reply.getGroup().getPeers().stream()
              .map(p -> p.getId().toString())
              .collect(Collectors.toSet());
          last = members;
          if (expected.equals(members)) {
            return;
          }
        } catch (IOException e) {
          // node mid-election or mid-shutdown; try the next one
        }
      }
      Thread.sleep(250);
    }
    throw new AssertionError("conf servers never became " + expected + "; last seen " + last);
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
}
