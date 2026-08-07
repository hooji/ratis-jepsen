# ratis-jepsen — M0 design (walking skeleton)

*Drafted 2026-08-04 against `PLAN.md` as of the Q1/Q4/Q5/Q7 decisions.
Scope: milestone M0 only, with seams left where M1–M3 attach. Every Ratis
identifier named here was verified to exist at tag `ratis-3.2.2` during the
2026-08 evaluation (spike traces, `RAFT_LIBRARY_EVALUATION.md` §3/§8).*

*This is the M0 design and stays one: M1–M5 attached at the seams it
left rather than replacing it. Where a later milestone changed something
stated here, the change appears as a **dated amendment** beside the
original text, never as a rewrite. §2.6's deployment contract is the one
section that is a live contract rather than history — env and harness
both build to it, and it wins over `env/README.md`'s copy. For the
current option surface, `clojure -M:run test --help` is authoritative;
for the current outcome table, `harness/src/ratis_jepsen/outcome.clj`'s
docstring is.*

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

*Amended 2026-08-07 (Job 13, recording what M2/M3 added): the launcher
also accepts `--join` (M2 membership pool — see §2.6's join-mode row)
and `--retry-cache-expiry-ms <ms>` (the M3/Q14 test lever, which
overrides `raft.server.retrycache.expirytime`; absent, the Ratis default
60 s window is untouched). `bin/ratis-kv --help` prints the current
contract.*

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
| `raft.server.snapshot.auto.trigger.threshold` | 4096 | low enough that ordinary runs snapshot; M2 was expected to lower it further to force install-snapshot |
| `raft.server.log.purge.upto.snapshot.index` | `true` | reclaim regardless of laggards; makes install-snapshot reachable once a follower falls behind a purge (M2's lever) |
| `raft.client.rpc.request.timeout` | 3 s (default) | must exceed worst-case commit latency; jepsen op timeout sits above it (§2.4) |

Client-side (harness): retry policy per §2.3 — never the library default
(`retryForeverNoSleep`).

*Amended 2026-08-07 (Job 13): the profile above is what the SUT still
sets, unchanged — including the 4096 threshold. **M2 did not lower it.**
Job 07 found that kill + client-triggered snapshot + restart cannot
reach install-snapshot at these settings at all (its first gate failed
correctly with `:no-install-snapshot-evidence`); what reaches it is a
sustained write stream that crosses the server's purge-gap milestones
behind a held-back follower, so the `snapshot-churn` schedule carries
workload defaults (`--rate 1.4 --ops-per-key 800`) instead of a
different server config. The only server-config knob a run can change is
`--retry-cache-expiry-ms`, and that is a test lever (§1.2), never part
of the profile.*

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
  .setRetryPolicy(...).build()`. One worker = one
  `ClientId` = one callId stream (Q2). **Retry policy (amended
  2026-08-05, ratifying Review 05): bounded fixed-sleep same-callId
  retries (4 × 200 ms), not `noRetry()`** — a deposed leader's appended
  entries can commit under its successor while the client receives
  NotLeaderException, so NLE is not proof of non-application and the
  original design let the harness convict healthy clusters. Same-callId
  retries are deduplicated by the server retry cache, so a
  step-down-committed write's retry returns the cached true outcome;
  only the exhausted residual is `:info` (Q3, as amended in PLAN).
  (M3's increment workload deliberately runs
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
| `NotLeaderException` | **`:info`** (amended 2026-08-05: a deposed leader may have appended the entry and it can commit under the successor — Review 05; retries usually resolve this before it surfaces) | `:fail` |
| `LeaderNotReadyException` | **`:info`** (same amendment) | `:fail` |
| `ResourceUnavailableException` | `:fail` (admission control rejects pre-append) | `:fail` |
| `GroupMismatchException` | `:fail` + flag run (setup bug, not SUT bug) | same |
| `StateMachineException` | `:fail` in M0 (our SM never throws from apply; reaching this = SUT bug ⇒ also flag) | `:fail` |
| `ReadException` / `ReadIndexException` | — | `:fail` (read never happened) |
| `TimeoutIOException`, generic `IOException`, `AlreadyClosedException`, interrupt | **`:info`** (may or may not have applied) | `:fail` |

*Amended 2026-08-07 (Job 13): the table above is the M0 original plus
the 2026-08-05 NotLeader/LeaderNotReady amendment. Later jobs added rows
to the implementation, each with its own unit test: `ServerNotReadyException`
(`:info` write / `:fail` read), `RaftRetryFailureException` with a null
cause (`:info` — what a retry-exhausted NotLeader/LeaderNotReady
surfaces as after the §2.3 amendment) and with a non-null cause
(`:info`, cause preserved in `:error`), `LeaderSteppingDownException`
(definite `:fail` — a pre-append admission reject; Job 07 added it after
147 pessimistic `:info`s from one transfer run pushed knossos out of
memory), and the harness-side `java.util.concurrent.TimeoutException`
(`:info` write / `:fail` read). The ADD op of the M3 counter workload
classifies as a write. **The live table is the docstring of
`harness/src/ratis_jepsen/outcome.clj`**; this section is its M0
ancestor.*

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

*Amended 2026-08-07 (Job 13): **the elle migration did not happen.**
knossos with the `cas-register` model is still the register checker, and
the budget is still what bounds it — which is why per-kind workload
defaults exist (`--rate`, `--ops-per-key`, `--key-count`; the
sustained-stream kinds and `unsync-drop-all` each carry their own). The
migration is banked as BACKLOG 13, with the evidence for why it would
pay: analysis cost varies ~2× by host, and three preserved runs in
`docs/RUNS.md` were pushed out of memory by `:info` mass. What *was*
added on top of M0's checkers: the M1 liveness checker (as planned), the
M3 counter checker, and the evidence checkers (install-snapshot,
membership conf transitions, joiner installs, durability faults, retry
counts, applied rolls) that fail a run whose fault never demonstrably
happened.*

## 2.6 Deployment contract (pinned 2026-08-04; env and harness both build to this)

| Item | Value |
|---|---|
| Nodes | `n1..n7` (`n1..n5` initial voters; `n6`/`n7` dormant pool), user `root`, passwordless ssh from `control` |
| Raft port | `6000` on every node |
| Install dir | `/opt/ratis-kv` (tarball unpacked: `/opt/ratis-kv/bin/ratis-kv`, `/opt/ratis-kv/lib/`) |
| Storage dir | `/var/lib/ratis-kv` |
| Log | stdout captured to `/var/log/ratis-kv.log` |
| Startup line | after `RaftServer.start()`, stdout emits `ratis-kv server started: id=<id> address=<host:port> storage=<dir> group=<uuid> peers=<list>` — the boot-await signal for env validation and `db.clj` (confirmed present at Job 01's merge; changing it is a breaking change requiring a brief) |
| Storage dir under `--durability` (M4, Job 11) | `/var/lib/ratis-kv` is unchanged *for the server* — it becomes a lazyfs FUSE mount over the backing directory `/var/lib/ratis-kv.root`. The harness proves the mount per node at setup and aborts the run loudly if it cannot. Nothing exists (no mount, no lazyfs process) unless the run asks for it |
| Both versions installed (M5, Job 12) | under `--mixed-version OLD,NEW` each node holds both tarballs and `/opt/ratis-kv` is a symlink flipped per node; the contract paths above are otherwise unchanged |
| Join mode (M2, ratified at Job 08 merge) | `bin/ratis-kv --id <id> --peers <full 7-node address book> --storage /var/lib/ratis-kv --join` starts a server that forms **no group**: on fresh storage it hosts nothing until bootstrapped (`GroupManagementApi.add`, then committed by `setConfiguration`); existing storage is recovered instead, making `--join` the restart mode for dynamically-joined nodes. The contract startup line is emitted **unchanged** (in join mode `peers=` is the launch address book, not a formed conf), preceded by `ratis-kv join mode: id=<id> formed no group; awaiting GroupManagementApi.add (existing storage is recovered instead)` |

## 3. Environment — `env/`

- One multi-arch image (arm64 dev / x86_64 CI): Debian base + OpenJDK 21 +
  sshd (jepsen control convention) + iproute2/iptables/psmisc. Nodes and
  control share the image; control adds the Clojure toolchain with a warmed
  deps cache.
- `docker compose`: `control`, `n1..n7`, one bridge network, static
  hostnames, `privileged: true` (iptables + SIGSTOP now, FUSE experiment
  later). **n6/n7 exist in the topology but run no SUT in M0** — they are
  the Q5 membership pool, pre-provisioned so M2 changes no plumbing.
*Amended 2026-08-07 (Job 13): the image is `ubuntu:24.04`-based and is
**built and validated on x86_64 only**. Nothing in the runtime image is
arch-pinned, so an arm64 build is expected to work, but no arm64 machine
has exercised it — treat it as untested (PLAN Q8's "multi-arch from day
one" is therefore only half-delivered). The lazyfs build stage added at
M4 is amd64-only by choice; on other architectures `/opt/lazyfs` is
empty and a `--durability` run fails loudly at mount proof instead of
silently testing nothing. Since M2, n6/n7 do run the SUT — on
membership-bearing kinds only, which is what the pool was pre-provisioned
for. `env/README.md` carries the operational detail.*

- Entry point: `env/run.sh up|test|down` so dev and CI invoke identically
  (`test` = `clojure -M:run test --workload register --nemesis partition
  --ratis-version 3.2.2 ...` on the control node; `store/` bind-mounted out).

## 4. CI shape (lands in M1 — after the repo goes public, per PLAN Q7)

Recorded now so M0 doesn't foreclose it: `workflow_dispatch` **only —
no cron** (owner, 2026-08-04: cadence stays manual until the planned
donation offer to the Ratis project, whose maintainers set their own
schedule) with inputs `ratis-version`, `scenario`, `time-limit`;
jobs = build SUT tarball once (upload as artifact), then a **matrix job per
scenario** pulling the tarball, `env/run.sh up && test`, always-upload
`store/**` (compressed) with a sane retention, `timeout-minutes: 60`.
Nothing in M0's env may assume more than one scenario per machine.

*Amended 2026-08-07 (Job 13), recording what `.github/workflows/jepsen.yml`
actually offers as built (Job 06, extended by Jobs 09/11/12): the shape
above holds — `workflow_dispatch` only, `build-sut` then a matrix job per
scenario, always-upload compressed stores at 7-day retention,
`timeout-minutes: 60`. The differences are: the input is **`scenarios`**
(a comma-separated list parsed into the matrix by a step inside
`build-sut`, which also validates it), not a single `scenario`; alongside
`time-limit` and `ratis-version` there are now `mixed-version` (the
OLD,NEW pair the `mv-*`/`rolling-upgrade` tokens require) and
`ratis-repo-url` (an extra Maven repository, for a release candidate not
yet on Central); `build-sut` builds one tarball per distinct version the
dispatch needs; and a third job, **`red-gate`**, runs a short seeded-bug
scenario that passes only if the harness fails it *and* a `results.edn`
carries `:valid? false` — an infrastructure failure cannot masquerade as
a catch. The durability tokens are deliberately absent from the default
list (they make every runner build lazyfs).*

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
