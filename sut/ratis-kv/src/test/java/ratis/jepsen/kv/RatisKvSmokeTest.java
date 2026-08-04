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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ratis.jepsen.kv.MiniCluster.send;
import static ratis.jepsen.kv.MiniCluster.sendReadOnly;

import java.nio.file.Path;
import java.time.Duration;

import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.RaftClientReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The acceptance smoke test (brief, criterion 3): three real RaftServers in
 * one JVM on 127.0.0.1 ephemeral ports, driven through a real RaftClient.
 */
class RatisKvSmokeTest {

  @Test
  @Timeout(300)
  void smoke(@TempDir Path tmp) throws Exception {
    try (MiniCluster cluster = MiniCluster.start(tmp, 3, null)) {

      try (RaftClient client = cluster.newClient()) {
        // (a) PUT / GET / GET-missing. The first op rides out leader election.
        assertEquals("OK", MiniCluster.sendUntilSuccess(client, "PUT k 1", Duration.ofSeconds(60)));
        assertEquals("VAL 1", sendReadOnly(client, "GET k"));
        assertEquals("ABSENT", sendReadOnly(client, "GET missing"));

        // (b) CAS semantics: success, mismatch (reports current), absent.
        assertEquals("OK", send(client, "CAS k 1 2"));
        assertEquals("MISMATCH 2", send(client, "CAS k 1 3"));
        assertEquals("ABSENT", send(client, "CAS absent 1 2"));

        // Malformed input over the real wire replies ERR, on both paths,
        // instead of throwing raw exceptions back at the client.
        assertEquals("ERR PUT expects 2 arguments, got 1", send(client, "PUT k"));
        assertEquals("ERR GET expects 1 argument, got 2", sendReadOnly(client, "GET k extra"));

        // (c) Trigger a snapshot (SnapshotManagementApi with force, i.e.
        // creation gap 1) and observe a snapshot.<term>_<index> file under
        // some server's storage.
        final RaftClientReply snapshotReply =
            client.getSnapshotManagementApi().create(true, 30_000);
        assertTrue(snapshotReply.isSuccess(), "snapshot creation request failed");
        final Path snapshotFile = cluster.awaitSnapshotFile(Duration.ofSeconds(30));
        assertTrue(
            MiniCluster.SNAPSHOT_FILE.matcher(snapshotFile.getFileName().toString()).matches(),
            "unexpected snapshot file name: " + snapshotFile);
      }

      // (d) Restart persistence: close all three servers, rebuild with
      // RECOVER on the same dirs, and the last committed value survives.
      cluster.restartAll();
      try (RaftClient client = cluster.newClient()) {
        assertEquals("VAL 2",
            MiniCluster.sendReadOnlyUntilSuccess(client, "GET k", Duration.ofSeconds(60)));
      }
    }
  }
}
