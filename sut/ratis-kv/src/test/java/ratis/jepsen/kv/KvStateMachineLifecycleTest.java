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

import java.nio.file.Path;
import java.time.Duration;

import org.apache.ratis.client.RaftClient;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.util.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the Job 08 find: the live install-snapshot path
 * pauses the state machine ({@code ServerState.installSnapshot}) and
 * {@code StateMachineUpdater.reload()} hard-asserts the PAUSED lifecycle
 * state, closing the whole division on failure —
 * {@code BaseStateMachine.pause()} is an empty method, so a state machine
 * that does not manage the lifecycle itself gets its division killed by
 * the first streamed snapshot install (observed live: every install
 * receiver in the combined membership+snapshot-churn runs crashed with
 * {@code IllegalStateException} at {@code StateMachineUpdater.reload} and
 * self-closed). This test drives the exact pause → reinitialize sequence
 * the updater performs, through a real initialized server.
 */
class KvStateMachineLifecycleTest {

  @Test
  @Timeout(120)
  void pauseAndReinitializeFollowTheReloadContract(@TempDir Path tmp) throws Exception {
    try (MiniCluster cluster = MiniCluster.start(tmp, 1, null)) {
      try (RaftClient client = cluster.newClient()) {
        // Ride out the single-node election; proves the division runs.
        assertEquals("OK",
            MiniCluster.sendUntilSuccess(client, "PUT k 1", Duration.ofSeconds(60)));
      }

      final StateMachine sm =
          cluster.server("n1").getDivision(Main.GROUP_ID).getStateMachine();
      assertEquals(LifeCycle.State.RUNNING, sm.getLifeCycleState(),
          "initialize must leave the state machine RUNNING");

      // What ServerState.installSnapshot does before replacing storage:
      sm.pause();
      assertEquals(LifeCycle.State.PAUSED, sm.getLifeCycleState(),
          "pause() must reach PAUSED — StateMachineUpdater.reload() asserts "
              + "exactly this and closes the division otherwise");

      // What StateMachineUpdater.reload() does afterwards:
      sm.reinitialize();
      assertEquals(LifeCycle.State.RUNNING, sm.getLifeCycleState(),
          "reinitialize() after an install must resume to RUNNING");
    }
  }
}
