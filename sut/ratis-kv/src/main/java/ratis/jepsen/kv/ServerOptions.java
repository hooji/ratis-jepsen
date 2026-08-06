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

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed launcher options (the process contract, DESIGN 1.2; {@code --join}
 * added by Job 08 for the M2 membership pool):
 *
 * <pre>
 * --id n1 --peers n1=host1:6000,n2=host2:6000,... --storage /var/lib/ratis-kv
 *         [--join] [--seed-bug stale-reads]
 * </pre>
 *
 * @param id      this node's id; must appear in {@code peers}
 * @param peers   node id to {@code host:port} address, insertion-ordered.
 *                In join mode this is an address book (self entry supplies
 *                the bind port), not a group to form
 * @param storageDir raft storage directory
 * @param join    join mode: start the server without forming a group; it
 *                hosts nothing until bootstrapped via
 *                {@code GroupManagementApi.add} (existing storage, if any,
 *                is recovered instead)
 * @param seedBug the seeded bug to activate, or null for a correct server
 */
public record ServerOptions(
    String id, Map<String, String> peers, Path storageDir, boolean join, SeedBug seedBug) {

  /** Signals a CLI parsing/validation failure; the message is user-facing. */
  public static final class UsageException extends Exception {
    UsageException(String message) {
      super(message);
    }
  }

  public ServerOptions {
    peers = Collections.unmodifiableMap(new LinkedHashMap<>(peers));
  }

  /** This node's {@code host:port} from the peer list. */
  public String selfAddress() {
    return peers.get(id);
  }

  /** This node's listen port, taken from its own peer entry. */
  public int selfPort() {
    final String address = selfAddress();
    return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
  }

  /**
   * Parses command-line arguments. {@code --help} is handled by the caller
   * before parsing; unknown or invalid input throws {@link UsageException}.
   */
  public static ServerOptions parse(String[] args) throws UsageException {
    String id = null;
    String peersSpec = null;
    String storageSpec = null;
    boolean join = false;
    String seedBugSpec = null;

    for (int i = 0; i < args.length; i++) {
      final String flag = args[i];
      switch (flag) {
        case "--id" -> id = flagValue(args, ++i, flag);
        case "--peers" -> peersSpec = flagValue(args, ++i, flag);
        case "--storage" -> storageSpec = flagValue(args, ++i, flag);
        case "--join" -> join = true;
        case "--seed-bug" -> seedBugSpec = flagValue(args, ++i, flag);
        default -> throw new UsageException("unknown argument: " + flag);
      }
    }

    if (id == null) {
      throw new UsageException("--id is required");
    }
    if (peersSpec == null) {
      throw new UsageException("--peers is required");
    }
    if (storageSpec == null) {
      throw new UsageException("--storage is required");
    }

    final Map<String, String> peers = parsePeers(peersSpec);
    if (!peers.containsKey(id)) {
      throw new UsageException("--id " + id + " does not appear in --peers " + peersSpec);
    }

    final Path storageDir;
    try {
      storageDir = Path.of(storageSpec);
    } catch (InvalidPathException e) {
      throw new UsageException("invalid --storage path: " + storageSpec);
    }

    SeedBug seedBug = null;
    if (seedBugSpec != null) {
      seedBug = SeedBug.fromCliName(seedBugSpec);
      if (seedBug == null) {
        throw new UsageException("unknown --seed-bug mode: " + seedBugSpec
            + " (known: " + String.join(", ", knownSeedBugNames()) + ")");
      }
    }

    return new ServerOptions(id, peers, storageDir, join, seedBug);
  }

  private static String flagValue(String[] args, int index, String flag) throws UsageException {
    if (index >= args.length) {
      throw new UsageException(flag + " requires a value");
    }
    return args[index];
  }

  private static Map<String, String> parsePeers(String spec) throws UsageException {
    final Map<String, String> peers = new LinkedHashMap<>();
    for (String entry : spec.split(",", -1)) {
      final int eq = entry.indexOf('=');
      if (eq <= 0 || eq == entry.length() - 1) {
        throw new UsageException("malformed --peers entry (want id=host:port): " + entry);
      }
      final String peerId = entry.substring(0, eq);
      final String address = entry.substring(eq + 1);
      final int colon = address.lastIndexOf(':');
      if (colon <= 0 || colon == address.length() - 1) {
        throw new UsageException("malformed peer address (want host:port): " + entry);
      }
      final int port;
      try {
        port = Integer.parseInt(address.substring(colon + 1));
      } catch (NumberFormatException e) {
        throw new UsageException("malformed peer port: " + entry);
      }
      if (port < 1 || port > 65535) {
        throw new UsageException("peer port out of range: " + entry);
      }
      if (peers.put(peerId, address) != null) {
        throw new UsageException("duplicate peer id: " + peerId);
      }
    }
    return peers;
  }

  private static String[] knownSeedBugNames() {
    final SeedBug[] bugs = SeedBug.values();
    final String[] names = new String[bugs.length];
    for (int i = 0; i < bugs.length; i++) {
      names[i] = bugs[i].cliName();
    }
    return names;
  }
}
