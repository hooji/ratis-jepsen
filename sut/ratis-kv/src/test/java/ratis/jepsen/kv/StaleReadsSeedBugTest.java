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

import java.nio.file.Path;
import java.time.Duration;

import org.apache.ratis.client.RaftClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seeded-bug pre-validation (brief, criterion 4) — the future red-run's
 * foundation. With stale-reads enabled on all servers, a PUT followed
 * immediately by a linearizable GET must observe the old value at least once
 * across a bounded loop; with the flag off, the same loop must never observe
 * staleness.
 */
class StaleReadsSeedBugTest {

  private static final int ATTEMPTS = 20;

  @Test
  @Timeout(300)
  void staleReadsBugProducesStaleLinearizableReads(@TempDir Path tmp) throws Exception {
    final int stale = staleObservations(tmp, SeedBug.STALE_READS);
    assertTrue(stale >= 1,
        "expected at least one stale read in " + ATTEMPTS + " attempts with the bug seeded");
  }

  @Test
  @Timeout(300)
  void withoutTheBugReadsAreNeverStale(@TempDir Path tmp) throws Exception {
    assertEquals(0, staleObservations(tmp, null),
        "correct server must never serve a stale linearizable read");
  }

  /**
   * Runs {@value #ATTEMPTS} rounds of {@code PUT k <i>} immediately followed
   * by a linearizable {@code GET k}; counts reads that did not observe the
   * value just written.
   */
  private static int staleObservations(Path tmp, SeedBug seedBug) throws Exception {
    try (MiniCluster cluster = MiniCluster.start(tmp, 3, seedBug);
        RaftClient client = cluster.newClient()) {
      MiniCluster.sendUntilSuccess(client, "PUT k 0", Duration.ofSeconds(60));

      int stale = 0;
      for (int i = 1; i <= ATTEMPTS; i++) {
        assertEquals("OK", MiniCluster.send(client, "PUT k " + i));
        final String read = MiniCluster.sendReadOnly(client, "GET k");
        if (!("VAL " + i).equals(read)) {
          stale++;
        }
      }
      return stale;
    }
  }
}
