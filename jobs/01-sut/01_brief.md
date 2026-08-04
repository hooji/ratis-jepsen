# Job 01 — `sut/ratis-kv`: the Ratis KV system under test

*Coordinator brief, 2026-08-04.*

**Before anything else, read `jobs/README.md` — it is binding.** Then read
`docs/PLAN.md` and `docs/DESIGN.md`; this job implements DESIGN §1
(System under test) and item 1 of DESIGN §7. Where this brief and
DESIGN §1 overlap, they agree; if you find a contradiction, DESIGN wins
and your report's Deviations section says so.

## Context (one paragraph)

We are building a Jepsen harness for Apache Ratis. This job builds the
thing Jepsen will test: a small standalone KV server that embeds a Ratis
`RaftServer` (Raft consensus, 5 voters in production runs), speaks a tiny
text protocol over Ratis's own client API, snapshots its state, and has a
deliberately seeded bug mode used later to prove the harness can catch
violations. No Jepsen, no Docker, no Clojure in this job — pure Java.

## Deliverables

1. **Maven project at `sut/ratis-kv/`** — self-contained (own `pom.xml`,
   no parent): `groupId` `ratis-jepsen`, `artifactId` `ratis-kv`, version
   `0.1.0-SNAPSHOT`. Java 21 (`maven.compiler.release=21`; if your
   environment only has an older LTS ≥17, use it and record that in your
   report). Property `ratis.version` = **3.2.2**. Include the Maven
   Wrapper (`mvnw`) so no preinstalled Maven is assumed.
   Dependencies: `org.apache.ratis:ratis-server`, `ratis-grpc`,
   `ratis-metrics-default` (all `${ratis.version}`),
   `org.slf4j:slf4j-simple` (runtime logging to stdout); tests:
   `org.junit.jupiter:junit-jupiter`.
2. **`KvStateMachine`** — the replicated state machine (details below).
3. **Protocol codec** — encode/decode for the wire protocol (below), as
   its own small class so it is unit-testable without a cluster.
4. **Launcher** (`Main`) — CLI per the process contract below, wiring the
   production config profile.
5. **Seeded-bug mode** — `--seed-bug stale-reads` (below).
6. **Tarball packaging** — `mvn package` additionally produces
   `target/ratis-kv-<version>.tar.gz` containing `lib/` (all runtime
   jars) and `bin/ratis-kv` (executable launcher script that builds the
   classpath from `lib/*` and execs `java`). maven-assembly-plugin (or
   equivalent) — committed as config, never commit the tarball itself.
7. **Tests** — per Acceptance criteria.
8. **`jobs/01-sut/01_report.md`** — per `jobs/README.md`.

## Wire protocol (from DESIGN §1.4 — restated as the contract)

UTF-8 token strings carried in Ratis `Message`s. Keys match
`[A-Za-z0-9_-]+`; values are Java longs. Malformed input replies
`ERR <reason>` (never throws to the client as a raw exception).

| Request (write path) | Reply |
|---|---|
| `PUT <k> <v>` | `OK` |
| `CAS <k> <expect> <update>` | `OK` \| `MISMATCH <cur>` \| `ABSENT` |

| Request (read path) | Reply |
|---|---|
| `GET <k>` | `VAL <v>` \| `ABSENT` |

Writes travel `RaftClient.io().send(Message.valueOf(...))` → Raft log →
`applyTransaction` on every replica. Reads travel
`RaftClient.io().sendReadOnly(Message.valueOf(...))` → server-side
ReadIndex → `query`. A failed CAS (`MISMATCH`/`ABSENT`) is still a
committed log entry — that is intended ("the log is the lock").

## Process contract (launcher)

```
bin/ratis-kv --id n1 \
             --peers n1=host1:6000,n2=host2:6000,n3=host3:6000 \
             --storage /var/lib/ratis-kv \
             [--seed-bug stale-reads]
```

- `--id` must appear in `--peers`; group id is a **fixed constant UUID**
  compiled into the binary (must be identical on all peers).
- Foreground process, logs to stdout; non-zero exit on fatal startup
  errors (a failed start must be loud). `--help` prints usage, exit 0.
- All peers are voters from first boot; use
  `RaftStorage.StartupOption.RECOVER` (works for both fresh and existing
  storage dirs — this is what Ratis's own example does).

### Ratis configuration (the production profile — set in the launcher)

| Config key (authoritative string) | Value |
|---|---|
| `raft.server.storage.dir` | from `--storage` |
| `raft.server.read.option` | `LINEARIZABLE` |
| `raft.server.rpc.timeout.min` | 1 s |
| `raft.server.rpc.timeout.max` | 2 s |
| `raft.server.snapshot.auto.trigger.enabled` | `true` |
| `raft.server.snapshot.auto.trigger.threshold` | 4096 |
| `raft.server.log.purge.upto.snapshot.index` | `true` |
| gRPC port | from this node's `--peers` entry |

Use the typed setters on `RaftServerConfigKeys` / `GrpcConfigKeys`
(e.g. `RaftServerConfigKeys.setStorageDir(props, List.of(dir))`,
`RaftServerConfigKeys.Read.setOption(props, ...LINEARIZABLE)`,
`GrpcConfigKeys.Server.setPort(props, port)`); the key strings above are
the source of truth — locate the matching setter in the Ratis 3.2.2
source/javadoc.

## `KvStateMachine` (from DESIGN §1.5)

- `extends org.apache.ratis.statemachine.impl.BaseStateMachine`.
- State: `ConcurrentHashMap<String, Long>` (`applyTransaction` runs
  strictly serially on one thread, but `query` may run concurrently).
- `applyTransaction(TransactionContext trx)`: decode
  `trx.getLogEntry().getStateMachineLogEntry().getLogData()`; apply;
  call `updateLastAppliedTermIndex(entry.getTerm(), entry.getIndex())`;
  **return an already-completed future**
  (`CompletableFuture.completedFuture(Message.valueOf(reply))`) —
  completing asynchronously would forfeit ordering guarantees.
- `query(Message request)`: decode `GET`, answer from the map.
  Linearizability is enforced by the server's ReadIndex path, not here.
- Snapshots via `SimpleStateMachineStorage` (single file
  `snapshot.<term>_<index>`): `takeSnapshot()` serializes the map (plain
  `DataOutputStream` or `Properties` — your choice, documented), returns
  the applied index; `initialize`/`reinitialize` load the latest
  snapshot if present. **Follow the shape of Ratis's own
  `CounterStateMachine`** —
  `ratis-examples/src/main/java/org/apache/ratis/examples/counter/server/CounterStateMachine.java`
  at tag `ratis-3.2.2` in `apache/ratis` (fetch it via your GitHub
  tooling; it demonstrates initialize/reinitialize/takeSnapshot/query
  wiring exactly).

## Seeded-bug mode (from DESIGN §1.6)

With `--seed-bug stale-reads`: `query()` answers from a **shadow map**
to which committed entries are applied only after a 500 ms delay
(single-threaded scheduled executor is fine). Off by default; when on,
log a shouting startup banner (`*** SEEDED BUG ACTIVE: stale-reads ***`).
The flag must be reachable **only** via the CLI argument. Expose the
mode to tests via the same code path the CLI uses.

## File ownership

May create/modify: `sut/**`, `jobs/01-sut/01_report.md`. Nothing else.
**Parallel-safe with: none** (first implementation job).

## Acceptance criteria (each must appear in your report with the command + output excerpt)

1. `./mvnw -q -f sut/ratis-kv/pom.xml verify` passes: codec unit tests +
   the in-JVM smoke test below.
2. Codec unit tests cover: round-trip of every request/reply form;
   malformed inputs (`PUT k`, `CAS k 1`, empty, bad key chars, non-long
   value) yield `ERR ...` decodes rather than exceptions.
3. **Smoke test** (JUnit, real Ratis, in one JVM): start 3 `RaftServer`s
   on `127.0.0.1` ephemeral ports with temp storage dirs (this is
   standard Ratis practice — its own test suite runs multi-server
   in-JVM). Assert, via a real `RaftClient`:
   a. `PUT k 1` → `OK`; `GET k` → `VAL 1`; `GET missing` → `ABSENT`.
   b. `CAS k 1 2` → `OK`; `CAS k 1 3` → `MISMATCH 2`;
      `CAS absent 1 2` → `ABSENT`.
   c. A snapshot exists after triggering one: either write >threshold
      entries with a lowered threshold, or use the client's
      `SnapshotManagementApi` — then assert a `snapshot.<term>_<index>`
      file exists under some server's storage.
   d. Restart persistence: close all three servers, rebuild with
      `RECOVER` on the same dirs, `GET k` still returns the last value.
4. **Seeded-bug pre-validation** (this test is the future red-run's
   foundation): with stale-reads enabled on all 3 servers, a
   `PUT k <new>` followed immediately by a linearizable `GET k`
   observes the *old* value at least once across a bounded loop of
   attempts; with the flag off, the same loop never observes staleness.
5. `./mvnw -q -f sut/ratis-kv/pom.xml package` produces the tarball;
   untarring it yields `bin/ratis-kv` (executable) and `lib/*.jar`, and
   `bin/ratis-kv --help` exits 0 printing usage (no cluster needed).
6. Apache-2.0 license headers on every source file; no build artifacts
   committed; all changes inside declared file ownership.
7. `jobs/01-sut/01_report.md` present, following `jobs/README.md`.

## Non-goals (do not build)

Membership changes / `--join`, Docker, Clojure, follower-targeted reads,
the `ADD` increment op, metrics endpoints, TLS, multi-file snapshot
storage, performance tuning.

## Crib notes (verified against Ratis at tag `ratis-3.2.2` — trust these)

- Server assembly:
  `RaftServer.newBuilder().setServerId(RaftPeerId.valueOf(id))
  .setGroup(group).setStateMachine(sm).setProperties(props)
  .setOption(RaftStorage.StartupOption.RECOVER).build()`, then
  `.start()`; `close()` to stop.
- Group: `RaftGroup.valueOf(RaftGroupId.valueOf(UUID.fromString(...)),
  peers)`; peers via
  `RaftPeer.newBuilder().setId("n1").setAddress("host:port").build()`.
- Client: `RaftClient.newBuilder().setProperties(new RaftProperties())
  .setRaftGroup(group).build()`; blocking API:
  `client.io().send(Message.valueOf(s))` /
  `client.io().sendReadOnly(Message.valueOf(s))`; reply payload:
  `reply.getMessage().getContent().toStringUtf8()`.
- The three snapshot-relevant `StateMachine` members you'll implement:
  `initialize(RaftServer, RaftGroupId, RaftStorage)`, `reinitialize()`,
  `long takeSnapshot()` — plus `applyTransaction` / `query` as above.
  `SimpleStateMachineStorage.getSnapshotFile(term, index)` names the
  snapshot file for you.
- Known upstream quirk (do not trip over it): the client-side
  `AdminApi.setConfiguration(RaftPeer[], RaftPeer[])` overload is broken
  at 3.2.2 (RATIS-2640) — irrelevant to this job (no membership ops),
  noted so you don't use it in test scaffolding.
