# Job 01 report — `sut/ratis-kv`: the Ratis KV system under test

## Summary

`sut/ratis-kv` now exists: a self-contained Maven project (Java 21, Ratis
3.2.2 pinned via the `ratis.version` property) building a standalone KV
server that embeds a Ratis `RaftServer`, speaks the PUT/CAS/GET text
protocol over Ratis's client API, snapshots via `SimpleStateMachineStorage`,
packages itself as a `bin/` + `lib/` tarball, and carries the
`--seed-bug stale-reads` mode. All seven acceptance criteria pass; the
in-JVM smoke test runs three real RaftServers through PUT/GET/CAS, a forced
snapshot, and a RECOVER restart, and the seeded-bug pre-validation observes
staleness with the flag on and none with it off. The two decisions to review
hardest: (1) the smoke test triggers its snapshot via
`SnapshotManagementApi.create(force=true, …)` (the brief's second-listed
option) so the production profile's 4096 threshold stays untouched in tests;
(2) request decoding is a total function (malformed input becomes a
`Malformed` value that the state machine answers as `ERR <reason>`), while
reply decoding throws — replies only ever come from our own state machine,
so a bad one is a program bug.

## What was built

| File | One line |
|---|---|
| `sut/ratis-kv/pom.xml` | `ratis-jepsen:ratis-kv:0.1.0-SNAPSHOT`, Java 21, `ratis.version=3.2.2`, deps per brief, surefire + assembly wiring |
| `sut/ratis-kv/mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Maven Wrapper 3.3.4, `only-script` type (no jar — repo `.gitignore` bans `*.jar`); bootstraps Maven 3.9.11 |
| `sut/ratis-kv/src/assembly/dist.xml` | assembly descriptor: `tar.gz` with `bin/` (mode 0755) and `lib/` (all runtime jars), no base directory |
| `sut/ratis-kv/src/main/dist/bin/ratis-kv` | launcher script: classpath from `lib/*`, `exec java`, slf4j-simple forced to stdout |
| `src/main/java/ratis/jepsen/kv/KvCodec.java` | wire-protocol codec: sealed `Request`/`Reply` records, total request decode, strict reply decode; no Ratis imports (unit-testable without a cluster) |
| `src/main/java/ratis/jepsen/kv/KvStateMachine.java` | `BaseStateMachine` subclass shaped after `CounterStateMachine` @ ratis-3.2.2: `ConcurrentHashMap` state, serial `applyTransaction` returning completed futures, `query` for GET, single-file DataOutputStream snapshots + md5 sidecar, stale-reads shadow map |
| `src/main/java/ratis/jepsen/kv/SeedBug.java` | seeded-bug enum (`stale-reads`) + CLI-name mapping |
| `src/main/java/ratis/jepsen/kv/ServerOptions.java` | process-contract CLI parsing/validation (`--id`/`--peers`/`--storage`/`--seed-bug`), `UsageException` |
| `src/main/java/ratis/jepsen/kv/Main.java` | launcher: fixed group UUID `724d1912-848e-4e0f-a7e0-abbc16e54704`, production config profile, RECOVER startup, foreground + shutdown hook, `--help`/exit codes; `buildServer` is the single assembly path shared with tests |
| `src/test/java/ratis/jepsen/kv/KvCodecTest.java` | 36 unit tests: round-trips of every request/reply form, malformed-input matrix |
| `src/test/java/ratis/jepsen/kv/MiniCluster.java` | in-JVM N-server fixture on 127.0.0.1 ephemeral ports; builds servers by running argv through `ServerOptions.parse` → `Main.buildServer` (the literal CLI code path) |
| `src/test/java/ratis/jepsen/kv/RatisKvSmokeTest.java` | acceptance criterion 3 (a–d) plus malformed-over-the-wire ERR checks |
| `src/test/java/ratis/jepsen/kv/StaleReadsSeedBugTest.java` | acceptance criterion 4, both directions |

## How it was verified

Environment: OpenJDK 21.0.10, Linux x86_64. (`mvnw` bootstrapped its own
Maven 3.9.11 on first run, so no preinstalled Maven is assumed.)

**Criterion 1 — `verify` passes.** From the repo root (wrapper lives inside
the module; see Deviations):

```
$ sut/ratis-kv/mvnw -q -f sut/ratis-kv/pom.xml verify ; echo EXIT=$?
EXIT=0
```

Same build non-quiet:

```
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building tar: .../sut/ratis-kv/target/ratis-kv-0.1.0-SNAPSHOT.tar.gz
[INFO] BUILD SUCCESS
```

**Criterion 2 — codec unit tests.**

```
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml test -Dtest=KvCodecTest
[INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.307 s -- in ratis.jepsen.kv.KvCodecTest
```

Round-trips cover PUT/CAS/GET and OK/VAL/ABSENT/MISMATCH/ERR (including
`Long.MIN_VALUE`/`MAX_VALUE` and multi-word ERR reasons). The malformed
matrix includes exactly the brief's list — `PUT k`, `CAS k 1`, empty, bad
key chars (`PUT ke$y 1`), non-long value (`PUT k twelve`) — plus arity,
whitespace, case-sensitivity, overflow, and non-ASCII-key cases; each is
asserted to decode to `Malformed` without throwing and to produce a legal
`ERR <reason>` wire reply.

**Criterion 3 — smoke test** (3 real `RaftServer`s + real `RaftClient`, one
JVM, ephemeral ports, temp storage). One test method walks a–d in order;
excerpts from the `verify` log:

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.825 s -- in ratis.jepsen.kv.RatisKvSmokeTest
```

- (a)/(b): asserted reply strings `OK`, `VAL 1`, `ABSENT`, `OK`,
  `MISMATCH 2`, `ABSENT` (see the test source; any mismatch fails the run).
  Also over the wire: `PUT k` → `ERR PUT expects 2 arguments, got 1`,
  `GET k extra` → `ERR GET expects 1 argument, got 2`.
- (c): `SnapshotManagementApi.create(true, 30_000)` then a
  `snapshot.<term>_<index>` file appears —

  ```
  ... INFO ... StateMachine - n1: took snapshot snapshot.1_10 covering (t:1, i:10) (1 keys)
  ... INFO ... SnapshotManagementRequestHandler - n1@group-...: Successfully take snapshot at index 10 ...
  ```

- (d): all three servers closed, rebuilt with `RECOVER` on the same dirs;
  state survives (and the snapshot is what gets loaded):

  ```
  ... INFO ... StateMachine - n1: loaded snapshot snapshot.1_10 covering (t:1, i:10) (1 keys)
  ... n1@group-...: getLatestSnapshot(...) returns SingleFileSnapshotInfo(t:1, i:10):[.../n1/724d1912-.../sm/snapshot.1_10]
  ```

  followed by the test's `GET k` → `VAL 2` assertion passing.

**Criterion 4 — seeded-bug pre-validation.** Two tests, each on its own
3-node cluster: with `--seed-bug stale-reads` on all servers, 20 rounds of
`PUT k <i>` + immediate linearizable `GET k` must observe a non-current
value at least once (assert `stale >= 1`); with the flag off the same loop
asserts `stale == 0`.

```
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml test -Dtest=StaleReadsSeedBugTest
... WARN org.apache.ratis.statemachine.StateMachine - *** SEEDED BUG ACTIVE: stale-reads ***
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.66 s -- in ratis.jepsen.kv.StaleReadsSeedBugTest
```

**Criterion 5 — tarball.**

```
$ sut/ratis-kv/mvnw -q -f sut/ratis-kv/pom.xml -DskipTests package ; echo EXIT=$?
EXIT=0
$ ls sut/ratis-kv/target/*.tar.gz
sut/ratis-kv/target/ratis-kv-0.1.0-SNAPSHOT.tar.gz
$ tar -xzf ratis-kv-0.1.0-SNAPSHOT.tar.gz -C untar && ls untar untar/bin
untar:  bin  lib          untar/bin:  -rwxr-xr-x ratis-kv
$ ls untar/lib | head -4; ls untar/lib | wc -l
jakarta.annotation-api-1.3.5.jar ratis-client-3.2.2.jar ratis-common-3.2.2.jar ratis-grpc-3.2.2.jar
13
$ ./bin/ratis-kv --help >/dev/null; echo EXIT=$?
EXIT=0
```

`--help` prints the usage block (flags, seed-bug warning, the compiled-in
group UUID) with no cluster and no storage touched. Extra launcher checks
beyond the letter of the criterion: unknown flag / `--id` not in `--peers` /
unknown `--seed-bug` value each exit 2 with usage on stderr; a real boot
from the untarred layout logs
`ratis-kv server started: id=n1 address=127.0.0.1:39482 storage=...` on
stdout and shuts down cleanly on SIGTERM; a deliberately colliding gRPC
port makes the process terminate with exit status 1
(`ERROR ... Terminating with exit status 1: Failed to start Grpc server`) —
a failed start is loud. The `*** SEEDED BUG ACTIVE: stale-reads ***` banner
was confirmed via the real CLI flag from the tarball (logged by both the
launcher and the state machine).

**Criterion 6 — hygiene.** Every one of the 15 committed files contains the
Apache-2.0 header (checked by grep over `git status -uall`); `target/` and
the tarball are ignored by the repo `.gitignore` (`target/`, `*.tar.gz`);
all changes live under `sut/**` plus this report.

**Criterion 7** — this file.

## Deviations from the brief

- **Acceptance command spelling.** The brief writes
  `./mvnw -q -f sut/ratis-kv/pom.xml verify`, which implies a repo-root
  `mvnw`; file ownership is `sut/**` only, so the wrapper lives at
  `sut/ratis-kv/mvnw` and the verified command is
  `sut/ratis-kv/mvnw -q -f sut/ratis-kv/pom.xml verify` (identical
  semantics, run from the repo root). If a root-level wrapper is wanted, a
  coordinator-side symlink/copy is a one-liner.
- **CounterStateMachine reference fetch.** The brief says to fetch it from
  `apache/ratis` via GitHub tooling; this session's GitHub access is scoped
  to `hooji/ratis-jepsen` only, so I used the official `*-sources.jar`
  artifacts of the 3.2.2 release from Maven Central instead (same bytes as
  tag `ratis-3.2.2`: examples, server, server-api, client, common, grpc)
  and mirrored `initialize`/`reinitialize`/`takeSnapshot`/md5-sidecar/query
  wiring from `CounterStateMachine` exactly.
- **Maven Wrapper without the jar.** `wrapper:wrapper -Dtype=only-script`
  (wrapper 3.3.4): `mvnw` self-downloads the Maven distribution, no
  `maven-wrapper.jar` is committed — which the repo `.gitignore`'s `*.jar`
  rule would have rejected anyway. Deliverable "include the Maven Wrapper"
  is met; first `mvnw` run needs network access to repo.maven.apache.org.
- **Snapshot trigger in the smoke test.** Of the brief's two permitted
  options I used `SnapshotManagementApi.create(force=true, timeout)`
  (creation gap 1, verified against 3.2.2 source: a request-supplied gap
  overrides `raft.server.snapshot.creation.gap`), keeping the tests on the
  untouched production profile rather than lowering the auto-trigger
  threshold.

Documented choices the brief left open: snapshot serialization is
`DataOutputStream` (entry count, then `writeUTF(key)`/`writeLong(value)`
pairs, written in `TreeMap` order for determinism), plus the `.md5` sidecar
and `updateLatestSnapshot` registration that the 3.2.2 example performs. A
`GET` arriving on the write path (or `PUT`/`CAS` on the read path) answers
`ERR <reason>` rather than being served — the harness never routes them
that way, and strictness surfaces harness bugs early (DESIGN 1.4 treats
malformed traffic as harness bugs).

## Known gaps and risks

- **Install-snapshot is not exercised.** The restart test loads snapshots
  from local storage (`initialize`/`reinitialize`); a leader *installing* a
  snapshot on a lagging follower is an M2 harness scenario. The md5 sidecar
  is written specifically so that path won't be surprised later.
- **Shadow map loses its lag across restart.** In stale-reads mode,
  snapshot load copies the authoritative map into the shadow map and
  pending delayed applies are dropped on close. Fine for a seeded bug (the
  staleness window is what matters), but the shadow is *fresh* right after
  a restart until the next write.
- **Stale-test timing margin.** The bug test asserts ≥1 stale read in 20
  rounds; each round would have to straddle the 500 ms shadow delay (e.g.
  extreme GC pauses) for a round to *miss* — in the observed runs
  effectively every round is stale. The green-path test (`stale == 0`) has
  no timing dependence at all.
- **Ephemeral-port allocation race.** The fixture opens-and-closes server
  sockets to pick free ports; another process could grab one before the
  RaftServer binds. Accepted as standard practice; a collision fails loudly
  (as demonstrated: bind failure → exit 1) rather than corrupting a run.
- **Non-canonical long spellings are accepted on decode** (`PUT k +5`
  parses; encode never emits `+`). Values are still Java longs; harmless
  unless a future checker string-compares requests.
- `mvnw.cmd` (Windows) is committed as generated but untested — this
  environment is Linux-only.
- Client-visible behavior under faults (NotLeaderException mapping etc.) is
  deliberately untouched here — that taxonomy belongs to the harness's
  outcome map (DESIGN 2.4), not the SUT.

## Suggestions (out of scope)

- The repo has no root `LICENSE`/`NOTICE` yet (PLAN Q17 leans Apache-2.0 +
  NOTICE from the first commit); outside my file ownership, one small
  coordinator commit.
- Job 03 (harness db lifecycle) will await a startup line in the log;
  suggest pinning the exact contract now — this SUT emits
  `ratis-kv server started: id=<id> address=<host:port> storage=<dir> group=<groupId> peers=<map>`
  on stdout after `RaftServer.start()` returns.
- When M1 adds the `lost-cas-ack` seed, `SeedBug` + the `KvStateMachine`
  constructor are the intended plumbing points; the CLI/usage text already
  enumerates modes from the enum.
- If a repo-root `./mvnw` is wanted for the brief's literal acceptance
  command, add a root wrapper delegating to the module (or move the
  wrapper up) in a coordinator commit.
