# ratis-jepsen — M0 design (walking skeleton)

*Drafted 2026-08-04 against `PLAN.md` as of the Q1/Q4/Q5/Q7 decisions.
Scope: milestone M0 only, with seams left where M1–M3 attach. Every Ratis
identifier named here was verified to exist at tag `ratis-3.2.2` during the
2026-08 evaluation (spike traces, `RAFT_LIBRARY_EVALUATION.md` §3/§8).*

## 0. M0 scope and exit criteria

Build the smallest end-to-end thing that can be *wrong*: a 5-voter Ratis KV
cluster, a Jepsen register workload with a partition nemesis, linearizability
checking, running locally in Docker.

**Exit criteria (from PLAN §5):**
1. One green run: register workload + partition nemesis, checker passes.
2. One red run: the SUT's seeded-bug mode enabled, checker reports a
   linearizability violation. A harness that has never caught anything is
   not yet evidence.

Not in M0 (attach points noted below): crash-restart and pause nemeses (M1),
CI workflow (M1, after the repo goes public — PLAN Q7), membership and
snapshot churn (M2), follower reads (M2), increment workload (M3), RocksDB
state machine (post-M3 per Q4), lazyfs (M4).

## 1. System under test — `sut/ratis-kv`

### 1.1 Module

- Maven module `sut/ratis-kv`, Java **21** (LTS; Ratis ships Java 8 bytecode
  so any modern JDK works — 21 is what we build and test with).
- Dependencies: `org.apache.ratis:ratis-server:${ratis.version}` +
  `org.apache.ratis:ratis-grpc:${ratis.version}` +
  `org.apache.ratis:ratis-metrics-default:${ratis.version}` (without the
  default module, only the metrics *api* lands and metrics are no-ops —
  evaluation §6) + slf4j-simple.
- **`ratis.version` is a Maven property, default `3.2.2`** (PLAN Q15 leaning,
  adopted; 3.3.0 joins the matrix when released). The harness never depends
  on SUT internals — only on the wire protocol below — so version bumps are
  a property change.
- Packaging: `maven-assembly` tarball `ratis-kv-<version>.tar.gz` containing
  a lib/ dir of jars and a `bin/ratis-kv` launcher script. This is what the
  harness scp's to db nodes.

### 1.2 Process contract

```
bin/ratis-kv --id n1 \
             --peers n1=n1:6000,n2=n2:6000,n3=n3:6000,n4=n4:6000,n5=n5:6000 \
             --storage /var/lib/ratis-kv \
             [--seed-bug stale-reads]
```

- `--id` must appear in `--peers`. Group id is a fixed constant UUID
  (identical on every node — a Ratis requirement; evaluation spike A.1).
- Single process, foreground, logs to stdout (the harness redirects to
  `/var/log/ratis-kv.log` and collects it per run). Exit code non-zero on
  fatal startup errors — a failed start must be loud, not wedged.
- No dynamic membership in M0: all five peers are voters from first boot
  (`RaftServer.newBuilder().setGroup(...)` with the full group,
  `StartupOption.RECOVER` on restart). The M2 pool flow
  (`GroupManagementApi.add` + `setConfiguration`) replaces this bootstrap —
  the launcher grows a `--join` mode then; nothing in M0 blocks it.

### 1.3 Ratis configuration — the production profile (PLAN Q13)

Set in code, from the evaluation's "minimal correct production set", values
chosen for containerized shared-CPU runs:

| Key | Value | Why |
|---|---|---|
| `raft.server.storage.dir` | `--storage` arg | never the `/tmp` default |
| `raft.server.read.option` | `LINEARIZABLE` | the semantics under test; the `DEFAULT` mode legally serves stale reads and would be a standing false positive |
| `raft.server.rpc.timeout.min` / `.max` | 1 s / 2 s | LAN defaults (150–300 ms) cause spurious elections on noisy shared runners |
| `raft.server.snapshot.auto.trigger.enabled` | `true` | snapshots on from day one (they are a test target, not an extra) |
| `raft.server.snapshot.auto.trigger.threshold` | 4096 | low enough that ordinary runs snapshot; M2 lowers it further to force install-snapshot |
| `raft.server.log.purge.upto.snapshot.index` | `true` | reclaim regardless of laggards; makes install-snapshot reachable once a follower falls behind a purge (M2's lever) |
| `raft.client.rpc.request.timeout` | 3 s (default) | must exceed worst-case commit latency; jepsen op timeout sits above it (§2.4) |

Client-side (harness): retry policy per §2.3 — never the library default
(`retryForeverNoSleep`).

### 1.4 Wire protocol (SUT command protocol)

Plain UTF-8 token strings inside Ratis `Message`s — trivially greppable in
logs, no serialization dependency. M0 constrains keys to `[A-Za-z0-9_-]+`
and values to Java longs (Jepsen register workloads use small ints), which
makes tokenization unambiguous. Revisit only if a workload needs richer
values.

Write path (`RaftClient.io().send(Message.valueOf(...))` → log entry →
`applyTransaction` on every replica):

| Request | Reply | Notes |
|---|---|---|
| `PUT <k> <v>` | `OK` | unconditional write |
| `CAS <k> <expect> <update>` | `OK` \| `MISMATCH <cur>` \| `ABSENT` | outcome decided at apply — a failed CAS is still a committed entry ("the log is the lock"); its reply is cached by the retry cache like any other |

Read path (`RaftClient.io().sendReadOnly(Message.valueOf(...))` → ReadIndex
→ `query(Message)`):

| Request | Reply |
|---|---|
| `GET <k>` | `VAL <v>` \| `ABSENT` |

Malformed requests reply `ERR <reason>` (and are a harness bug, not an
`:info`). M3 adds `ADD <k> <delta>` → `VAL <new>` for the exactly-once
increment workload; M2 adds nothing (follower reads reuse `GET` via
`sendReadOnly(msg, followerId)`).

### 1.5 State machine

`KvStateMachine extends BaseStateMachine` (the evaluation's spike A.1 is the
template; all signatures verified):

- State: `ConcurrentHashMap<String, Long>` — `applyTransaction` is invoked
  strictly serially on the single `StateMachineUpdater` thread, but `query`
  may run in parallel with it, hence the concurrent map.
- `applyTransaction(TransactionContext trx)`: decode
  `trx.getLogEntry().getStateMachineLogEntry().getLogData()`, apply PUT/CAS,
  `updateLastAppliedTermIndex(...)`, **complete the returned future
  synchronously** (total order for free; the async-completion trap is
  evaluation friction-diary item 1).
- `query(Message request)`: decode GET, read the map. Linearizability comes
  from the server's ReadIndex path, not this method.
- Snapshots: `SimpleStateMachineStorage` + one serialized-map file
  (`takeSnapshot()` writes `snapshot.<term>_<index>`, returns the applied
  index; `initialize`/`reinitialize` load the latest). Single-file is stock
  Ratis (`SingleFileSnapshotInfo` *is* a `FileListSnapshotInfo`); the
  multi-file custom `StateMachineStorage` arrives with the RocksDB stage
  (Q4), not before.

### 1.6 Seeded-bug mode (the test of the test)

`--seed-bug stale-reads`: `query()` answers from a shadow map that applies
each committed entry only after a fixed delay (default 500 ms, executor-
driven). Under concurrent writes this *must* produce non-linearizable reads.
Properties: off by default; startup logs a shouting banner; the flag is
plumbed via the launcher only (no config-file path), so a production copy
can't quietly inherit it. M1 may add a second seed (`lost-cas-ack`:
acknowledge CAS before apply) — one seed is enough for M0's exit gate.

## 2. Harness — `harness/`

### 2.1 Toolchain and layout

Clojure with **deps.edn + tools.build** (PLAN Q16, adopted-by-design;
Leiningen has more copy-paste precedent but deps.edn is where the ecosystem
went, and we're writing from scratch anyway). Pinned current Jepsen release.
Ratis client enters the harness JVM as ordinary Maven deps —
`ratis-client` + `ratis-grpc` at the run's target version. The fully
relocated thirdparty jar means zero interop classpath conflict (evaluation
§5 — this was a Q1 selling point).

Namespaces:

```
ratis-jepsen.core       ; CLI, test-map assembly, run orchestration
ratis-jepsen.db         ; node lifecycle: install / start / kill / wipe / logs
ratis-jepsen.client     ; RaftClient interop + THE OUTCOME MAP (§2.4)
ratis-jepsen.workload.register  ; r/w/cas ops, generators, checker wiring
ratis-jepsen.nemesis    ; M0: stock partitioner; custom nemeses land M2
```

### 2.2 db lifecycle (jepsen `DB` protocol)

- `setup!`: upload + untar the SUT tarball (built by CI/dev beforehand;
  the harness does not run Maven), write per-node launcher env, start via
  `start-stop-daemon` with pidfile, await "server started" in the log with
  a deadline (a wedged start must fail the run, fast).
- `teardown!`: `kill -9` by pidfile, wipe `--storage` and logs.
- `LogFiles`: `/var/log/ratis-kv.log` — collected into `store/` per node.
- Kill/restart primitives live here from day one (M1's crash nemesis just
  calls them).

### 2.3 Client lifecycle (jepsen `Client` protocol; Q1/Q2/Q3 decisions)

- `open!`: one `RaftClient` per Jepsen worker process —
  `RaftClient.newBuilder().setProperties(...).setRaftGroup(GROUP)
  .setRetryPolicy(RetryPolicies.noRetry()).build()`. One worker = one
  `ClientId` = one callId stream (Q2); the register workload uses
  **`noRetry()`** so every ambiguity surfaces as `:info` rather than being
  laundered by the library (Q3). (M3's increment workload deliberately runs
  a *second* client config with bounded retries — the retry cache is its
  subject.)
- `invoke!`: ops map 1:1 to §1.4 messages. Reads use leader-routed
  `sendReadOnly` in M0 (follower-targeted reads are M2). Each invocation is
  additionally wrapped in a harness-side timeout slightly above the client
  rpc timeout, mapping to `:info` for writes / `:fail` for reads.
- `close!`: `client.close()`.

### 2.4 The outcome map — correctness-critical, unit-tested

One namespace, one table, exhaustive `condp instance?` over Ratis's
exception types; anything unrecognized is `:info` for writes (safe
pessimism) and logged loudly for triage. Reads may always be `:fail` on
exception (no side effect); writes distinguish definite-not-applied from
ambiguous. Initial table (types verified at 3.2.2):

| Outcome from client | Register write (`PUT`/`CAS`) | Read (`GET`) |
|---|---|---|
| reply `isSuccess` | `:ok` (CAS `MISMATCH`/`ABSENT` payload ⇒ `:fail` with `:error :precondition` — the *op* failed, definitively, by design) | `:ok` |
| `NotLeaderException` | `:fail` (definite; not appended) | `:fail` |
| `LeaderNotReadyException` | `:fail` | `:fail` |
| `ResourceUnavailableException` | `:fail` (admission control rejects pre-append) | `:fail` |
| `GroupMismatchException` | `:fail` + flag run (setup bug, not SUT bug) | same |
| `StateMachineException` | `:fail` in M0 (our SM never throws from apply; reaching this = SUT bug ⇒ also flag) | `:fail` |
| `ReadException` / `ReadIndexException` | — | `:fail` (read never happened) |
| `TimeoutIOException`, generic `IOException`, `AlreadyClosedException`, interrupt | **`:info`** (may or may not have applied) | `:fail` |

The SPI v3 Transient/Permanent/Indeterminate taxonomy is the intellectual
template (same classify-at-the-throw-site discipline); this table is the
harness's version of it and gets plain unit tests with fabricated
exceptions before any cluster run trusts it.

### 2.5 Workload and checking (M0; Q10 budget)

- Ops: `:r`, `:w v`, `:cas [old new]` over `jepsen.independent` keys.
- Budget: **5 keys × ≤400 ops/key, concurrency 10, time-limit 300 s** —
  sized so knossos (`linearizable` checker, `cas-register` model) completes
  in minutes. elle's rw-register replaces knossos as primary in M1; knossos
  stays for smoke runs.
- Also wired: `timeline/html` and `perf` plots (free diagnostics), and
  `unhandled-exceptions` surfacing.
- Generator: mixed r/w/cas at ~10 ops/s/worker; nemesis cycle 15 s on /
  15 s off random halves partition (`nemesis/partition-random-halves`).
- The liveness checker is M1 (needs nemesis-aware gating); M0 relies on
  jepsen's rate plots plus the run wall-clock cap to notice a wedged
  cluster.

## 3. Environment — `env/`

- One multi-arch image (arm64 dev / x86_64 CI): Debian base + OpenJDK 21 +
  sshd (jepsen control convention) + iproute2/iptables/psmisc. Nodes and
  control share the image; control adds the Clojure toolchain with a warmed
  deps cache.
- `docker compose`: `control`, `n1..n7`, one bridge network, static
  hostnames, `privileged: true` (iptables + SIGSTOP now, FUSE experiment
  later). **n6/n7 exist in the topology but run no SUT in M0** — they are
  the Q5 membership pool, pre-provisioned so M2 changes no plumbing.
- Entry point: `env/run.sh up|test|down` so dev and CI invoke identically
  (`test` = `clojure -M:run test --workload register --nemesis partition
  --ratis-version 3.2.2 ...` on the control node; `store/` bind-mounted out).

## 4. CI shape (lands in M1 — after the repo goes public, per PLAN Q7)

Recorded now so M0 doesn't foreclose it: `workflow_dispatch` (+ nightly
cron once public) with inputs `ratis-version`, `scenario`, `time-limit`;
jobs = build SUT tarball once (upload as artifact), then a **matrix job per
scenario** pulling the tarball, `env/run.sh up && test`, always-upload
`store/**` (compressed) with a sane retention, `timeout-minutes: 60`.
Nothing in M0's env may assume more than one scenario per machine.

## 5. Validation procedure (the M0 exit gate)

1. **Green**: `run.sh test` with seed-bug off → checker `:valid? true`;
   archive the store as the reference-green example.
2. **Red**: same run with `--seed-bug stale-reads` on all nodes → checker
   `:valid? false` with a concrete non-linearizable read in the analysis.
   Archive as the reference-red example.
3. **Table check**: outcome-map unit tests green; plus one manual partition
   run eyeballed to confirm `:info` ops appear only during faults (a flood
   of `:info` in calm phases means the mapping or timeouts are wrong).

Both archived stores get a short `docs/RUNS.md` entry (what, when, version,
verdict) — the promised "summaries in docs, artifacts elsewhere" ledger.

## 6. Risks and open edges (tracked, not blocking M0)

- **knossos blow-up** if the ops/key budget creeps — hard-cap in the
  generator, not in prose. elle migration in M1 removes the cliff.
- **Container clock/scheduling jitter** on shared CI — timeouts already
  raised; if spurious elections still show up in M1 CI runs, raise further
  before blaming Ratis.
- **`sendReadOnly` under partition** may block up to read timeout (10 s
  server-side) — the harness op timeout must stay below jepsen's process
  starvation threshold; tune in M0 if workers stall.
- **Decisions adopted-by-design here, flag if you disagree** (PLAN owners'
  ledger): Q15 = pin 3.2.2 now / add 3.3.0 at release; Q16 = deps.edn;
  JDK 21 for SUT and harness; single-file snapshots until the RocksDB
  stage; text-token wire protocol with long values.

## 7. Build order (implementation sequence for M0)

1. `sut/ratis-kv`: state machine + launcher + tarball; unit-test protocol
   encode/decode + a 3-node in-JVM smoke test (MiniRaftCluster-style, per
   the spike recipe).
2. `env/`: image + compose + run.sh; manual 5-node boot, `PUT`/`GET` by
   hand via a tiny `Main` client.
3. `harness/`: db lifecycle → client + outcome map (+ its unit tests) →
   register workload → partition nemesis → checker wiring.
4. Exit-gate runs (§5), `docs/RUNS.md`, then M1 planning against reality.
