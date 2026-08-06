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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.junit.jupiter.api.Test;

/**
 * The {@code --retry-cache-expiry-ms} contract (Job 09 / PLAN Q14): the
 * flag wires {@code raft.server.retrycache.expirytime}; its ABSENCE must
 * leave the Ratis default (60 s) untouched — the production profile never
 * shrinks the window, only the Q14 red-by-design run does.
 */
class ServerOptionsRetryCacheTest {

  private static final String[] BASE = {
      "--id", "n1", "--peers", "n1=h1:6000", "--storage", "/tmp/s"};

  private static String[] with(String... extra) {
    final String[] args = new String[BASE.length + extra.length];
    System.arraycopy(BASE, 0, args, 0, BASE.length);
    System.arraycopy(extra, 0, args, BASE.length, extra.length);
    return args;
  }

  @Test
  void parsesAndWiresTheExpiryOverride() throws Exception {
    final ServerOptions options =
        ServerOptions.parse(with("--retry-cache-expiry-ms", "2000"));
    assertEquals(2000L, options.retryCacheExpiryMs());

    final RaftProperties properties = Main.buildProductionProperties(options);
    assertEquals(2000L,
        RaftServerConfigKeys.RetryCache.expiryTime(properties)
            .toLong(TimeUnit.MILLISECONDS));
  }

  @Test
  void absentFlagLeavesTheRatisDefaultUntouched() throws Exception {
    final ServerOptions options = ServerOptions.parse(BASE);
    assertNull(options.retryCacheExpiryMs());

    final RaftProperties properties = Main.buildProductionProperties(options);
    assertEquals(60_000L,
        RaftServerConfigKeys.RetryCache.expiryTime(properties)
            .toLong(TimeUnit.MILLISECONDS));
  }

  @Test
  void rejectsNonPositiveAndNonNumericValues() {
    assertThrows(ServerOptions.UsageException.class,
        () -> ServerOptions.parse(with("--retry-cache-expiry-ms", "0")));
    assertThrows(ServerOptions.UsageException.class,
        () -> ServerOptions.parse(with("--retry-cache-expiry-ms", "-5")));
    assertThrows(ServerOptions.UsageException.class,
        () -> ServerOptions.parse(with("--retry-cache-expiry-ms", "soon")));
  }
}
