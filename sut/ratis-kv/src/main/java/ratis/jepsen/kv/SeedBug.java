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

/**
 * Deliberately seeded bug modes (DESIGN 1.6), used to prove the harness can
 * catch violations. Off unless requested via the {@code --seed-bug} CLI
 * argument — there is intentionally no config-file or system-property path.
 */
public enum SeedBug {
  /**
   * {@code query()} answers from a shadow map to which committed entries are
   * applied only after a fixed delay, so linearizable reads can observe
   * stale state.
   */
  STALE_READS("stale-reads");

  private final String cliName;

  SeedBug(String cliName) {
    this.cliName = cliName;
  }

  /** The value accepted by {@code --seed-bug}. */
  public String cliName() {
    return cliName;
  }

  /** Returns the mode named by a CLI value, or null if unrecognized. */
  public static SeedBug fromCliName(String name) {
    for (SeedBug bug : values()) {
      if (bug.cliName.equals(name)) {
        return bug;
      }
    }
    return null;
  }
}
