# Review 01 report — Job 01: `sut/ratis-kv` — the Ratis KV system under test

Worker PR: #1 (`claude/sut-brief-instructions-jfwibr`, head `e094a6f`).
Reviewed in a detached worktree at that commit; every command below was run
by me in that worktree. Probe tests referenced below are review-local files
(never committed anywhere); the worker's code was not modified.

## Verdict: MERGE

## Justification

All seven acceptance criteria of `jobs/01-sut/01_brief.md` reproduce
independently in the worktree: `verify` passes with 39 green tests, the
codec test matrix covers the brief's malformed-input list, the smoke test
walks 3(a)–(d) against three real RaftServers, both arms of the seeded-bug
pre-validation hold, the tarball unpacks to a working cwd-independent
launcher, and hygiene (headers, ownership, no artifacts) is clean. The
review brief's two sharpest questions both resolve in the worker's favor:
every typed config setter in `Main` writes exactly the brief's authoritative
key string (verified against the `ratis-3.2.2` sources), and the
snapshot-load path is demonstrably live — with every Raft log segment
deleted on every node, the cluster still recovers the committed state from
snapshot files alone. The one substantive defect found (unbounded key
length can permanently poison snapshots) is unreachable by the M0–M3
harness workloads and is filed as non-blocking.

## What I verified

**Criterion 1 — `verify` passes.**

```
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml verify   # worktree root
[INFO] Tests run: 36, ... -- in ratis.jepsen.kv.KvCodecTest
[INFO] Tests run: 2,  ... -- in ratis.jepsen.kv.StaleReadsSeedBugTest
[INFO] Tests run: 1,  ... -- in ratis.jepsen.kv.RatisKvSmokeTest
[INFO] Tests run: 39, Failures: 0, Errors: 0, Skipped: 0
[INFO] Building tar: .../sut/ratis-kv/target/ratis-kv-0.1.0-SNAPSHOT.tar.gz
[INFO] BUILD SUCCESS               VERIFY_EXIT=0
```

The wrapper bootstrapped Maven 3.9.11 itself (`only-script`, wrapper 3.3.4
per `.mvn/wrapper/maven-wrapper.properties`); no preinstalled Maven used.

**Criterion 2 — codec unit tests.** Read `KvCodecTest.java` in full: 36
tests = 6 round-trip/wire-form tests over every request and reply form
(including `Long.MIN_VALUE`/`MAX_VALUE`), a 19-case malformed matrix
containing exactly the brief's five required inputs (`PUT k`, `CAS k 1`,
empty, `PUT ke$y 1`, `PUT k twelve`) plus arity/whitespace/case/overflow/
non-ASCII cases — each asserted to decode to `Malformed` without throwing
and to re-encode as a legal `ERR` reply — plus null-input, no-wire-form,
and 8 strict-reply-decode cases. All green in my run above.

**Criterion 3 — smoke test.** `RatisKvSmokeTest` does (a)–(d) in order
against 3 real `RaftServer`s built through the literal CLI path
(`ServerOptions.parse` → `Main.buildServer`) and a real `RaftClient`,
asserting exact reply strings (`OK`, `VAL 1`, `ABSENT`, `OK`,
`MISMATCH 2`, `ABSENT`), plus over-the-wire `ERR` on both paths. My run's
log shows the forced snapshot and the restart load:

```
... StateMachineUpdater] ... n1: took snapshot snapshot.1_10 covering (t:1, i:10) (1 keys)
... n1: loaded snapshot snapshot.1_10 covering (t:1, i:10) (1 keys)   (n2, n3 likewise)
```

followed by the post-RECOVER `GET k` → `VAL 2` assertion passing.

**Criterion 4 — seeded-bug pre-validation.** Both arms green in my
`verify` run (`Tests run: 2` above): ≥1 stale read in 20 rounds with the
flag on all three servers, 0 with it off. Deepened under Findings/probes.

**Criterion 5 — tarball.** From my build of the worktree:

```
$ tar -xzf .../target/ratis-kv-0.1.0-SNAPSHOT.tar.gz -C untar
$ ls -la untar/bin        →  -rwxr-xr-x ratis-kv
$ ls untar/lib | wc -l    →  13   (ratis-kv + ratis 3.2.2 jars + slf4j)
$ cd / && $UNTAR/bin/ratis-kv --help ; echo $?
Usage: ratis-kv --id <id> --peers ... [--seed-bug stale-reads] [--help]
...The raft group id is the compiled-in constant 724d1912-848e-4e0f-a7e0-abbc16e54704.
0
```

`--help` run from `/` — the script resolves its own location
(`BASH_SOURCE` → `exec java -cp $KV_HOME/lib/*`), so it is cwd-independent.

**Criterion 6 — hygiene.** All 15 committed non-markdown files carry the
Apache-2.0 header (checked by script over the full diff file list; the
job report `.md` carries none, matching the repo's convention for docs).
`git diff --numstat` shows zero binary files; `.gitignore` already covers
`target/`, `*.jar`, `*.tar.gz`. Diff touches only `sut/**` +
`jobs/01-sut/01_report.md` — inside declared ownership, and nothing else.

**Criterion 7 — report.** Present with all six required sections in the
required order; its claimed outputs reproduced (test counts, snapshot
file names, lib listing, exit codes — including the exact
`Terminating with exit status 1: Failed to start Grpc server` line).

### Review-brief emphasis points

**1. Config-profile truth check.** Fetched the official `ratis-3.2.2`
sources (`ratis-server-api`, `ratis-grpc` sources jars from Maven Central)
and traced every setter used in `Main.buildProductionProperties`
(`Main.java:149-160`) to the key constant it writes:

| Setter used | Key constant in ratis-3.2.2 source | Resolves to |
|---|---|---|
| `RaftServerConfigKeys.setStorageDir` | `STORAGE_DIR_KEY = PREFIX + ".storage.dir"`, `PREFIX = "raft.server"` | `raft.server.storage.dir` |
| `RaftServerConfigKeys.Read.setOption(...LINEARIZABLE)` | `Read.OPTION_KEY = PREFIX + ".option"`, `Read.PREFIX = "raft.server" + ".read"` | `raft.server.read.option` |
| `RaftServerConfigKeys.Rpc.setTimeoutMin` | `Rpc.TIMEOUT_MIN_KEY = PREFIX + ".timeout.min"`, `Rpc.PREFIX = "raft.server.rpc"` | `raft.server.rpc.timeout.min` |
| `RaftServerConfigKeys.Rpc.setTimeoutMax` | `Rpc.TIMEOUT_MAX_KEY = PREFIX + ".timeout.max"` | `raft.server.rpc.timeout.max` |
| `RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled` | `Snapshot.AUTO_TRIGGER_ENABLED_KEY = PREFIX + ".auto.trigger.enabled"`, `Snapshot.PREFIX = "raft.server.snapshot"` | `raft.server.snapshot.auto.trigger.enabled` |
| `RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold` | `Snapshot.AUTO_TRIGGER_THRESHOLD_KEY = PREFIX + ".auto.trigger.threshold"` | `raft.server.snapshot.auto.trigger.threshold` |
| `RaftServerConfigKeys.Log.setPurgeUptoSnapshotIndex` | `Log.PURGE_UPTO_SNAPSHOT_INDEX_KEY = PREFIX + ".purge.upto.snapshot.index"`, `Log.PREFIX = "raft.server.log"` | `raft.server.log.purge.upto.snapshot.index` |
| `GrpcConfigKeys.Server.setPort` | `Server.PORT_KEY = PREFIX + ".port"`, `Server.PREFIX = "raft.grpc" + ".server"` | `raft.grpc.server.port` |

Each setter writes exactly its own key (all are one-line
`set*(properties::set*, KEY, value)` bodies). Values match the brief
(1 s/2 s, `true`, 4096, `true`, LINEARIZABLE, port from the node's own
`--peers` entry). No wrong-but-compiling setter present. The port key is
empirically honored too: the ephemeral-port tests connect to the exact
allocated ports, and the occupied-port experiment fails at bind.

**2. Snapshot-load path is live, not dead code.** Read the load
implementation (`KvStateMachine.load()` — md5 verification when the
sidecar digest is present, `readInt`/`readUTF`/`readLong` back into the
map, `setLastAppliedTermIndex`), then forced the question with a probe
test: 3-node cluster, two PUTs, forced snapshot, quiesce, stop; **delete
every `current/log*` segment on every node** (keeping `raft-meta` and
`sm/`), assert-precondition that all nodes hold the same max snapshot
index, restart with RECOVER:

```
n1: loaded snapshot snapshot.1_4 covering (t:1, i:4) (1 keys)   (n2, n3 likewise)
GET k → VAL 42   ✓  (3 consecutive runs green)
```

With the log gone, replay cannot explain recovery; only
`initialize → reinitialize → load()` reading the snapshot file can. The
smoke test's restart alone would not have proven this (the log might have
still been present); this probe closes that gap.

**3. Seed-bug isolation.** (a) *Flag off ⇒ inert*: code-level — shadow
map and executor are only constructed when `seedBug == STALE_READS`
(`KvStateMachine.java:107-120`), the schedule call is guarded, `query`
reads the primary map; probe-level — after PUT/CAS/GET on a correct
cluster, no thread named `kv-stale-reads-shadow-applier` exists in the
JVM (asserted via `Thread.getAllStackTraces`). The single production
construction site is `Main.buildServer` fed by `ServerOptions.parse`;
grep confirms no env/system-property/config-file path to the flag.
(b) *Flag on ⇒ writes stay correct*: `applyTransaction` decides replies
against the primary map under `applyLock` (`KvStateMachine.java:160-163`)
before the shadow apply is even scheduled. Probe: with the bug on,
`PUT k 1` → `OK`, immediately `CAS k 1 2` → `OK` and `CAS k 1 9` →
`MISMATCH 2` — decisions the lagging shadow map (which had not yet applied
those writes) could not have produced — while the immediate linearizable
`GET k` was stale and converged to `VAL 2` only after the 500 ms lag.
Reads corrupted, writes intact. (c) *Negative-arm flakiness*: the
flag-off arm has no timing dependence at all — it asserts a linearizable
read on the same client after an acked write, which only Ratis itself
violating linearizability could break. The bug arm would need all 20
PUT→GET rounds to each straddle the 500 ms shadow delay to miss; rounds
are single-digit-ms on localhost. Flake risk negligible in both arms.

**4. Snapshot/apply concurrency.** `takeSnapshot` runs on the same
`StateMachineUpdater` thread as `applyTransaction` (visible in the log
thread names above), so the `applyLock` they share is never contended in
steady state; the only other holder is boot-time `load()`. The shadow
applier is a single thread, never takes `applyLock`, and cannot stall the
updater; FIFO order is guaranteed (serial submission from the updater +
`ScheduledThreadPoolExecutor`'s FIFO tie-break for equal trigger times +
monotonically increasing trigger times). Snapshot content is copied from
the **primary** map (also in bug mode) into a `TreeMap` under the lock —
deterministic byte stream for a given state, matching the md5 sidecar.

**5. Test-client retry masking.** `sendUntilSuccess`/
`sendReadOnlyUntilSuccess` appear at exactly three call sites, all
legitimate first-contact-after-boot/restart (smoke first PUT, smoke first
GET after RECOVER, seed-bug test's initial `PUT k 0`). Every other
assertion uses bare `send`/`sendReadOnly` and asserts the exact reply
string, so a genuine op failure cannot be laundered into a pass. The
client's own bounded retry policy (150 × 200 ms) only re-submits failed
RPCs under the retry cache's `(ClientId, callId)` dedup; it cannot alter
reply contents.

**6. Launcher/tarball beyond `--help`.** All from the untarred layout:

- Real boot: `ratis-kv server started: id=n1 address=127.0.0.1:36010 ...`
  on stdout; process survives; `kill -TERM` → shutdown hook closes the
  RaftServer (`SegmentedRaftLogWorker close()` … `JvmPauseMonitor Stopped`)
  and the process exits.
- Occupied port (listener pre-bound on the peer port):
  `ERROR ... Terminating with exit status 1: Failed to start Grpc server`,
  **exit 1**.
- Unwritable storage (`--storage /dev/null/sub`):
  `ERROR ... n1-RaftServerProxy has failed (STARTING -> EXCEPTION)` +
  `ratis-kv n1 failed to start`, **exit 1**.
- Usage errors: unknown flag / `--id` not in `--peers` / unknown
  `--seed-bug` value → usage on stderr, **exit 2**. `--help` → **exit 0**.

### Probes beyond the worker's tests (reviews/README rule 3)

1. **Double-start on one storage dir** (review-brief suggestion): second
   process, same `--storage`, different port →
   `Unable to acquire file lock on ... in_use.lock` /
   `It appears that another process has already locked the storage
   directory`, retries 5×, exits 1; first process unharmed and still
   serving. Loud and comprehensible, no corruption.
2. **Raft-log ablation** (emphasis 2 above) — snapshot-load path proven
   live, 3/3 green.
3. **Seed-bug isolation probe** (emphasis 3 above) — both directions.
4. **Adversarial codec input — very long key** (review-brief suggestion):
   found the one real defect; see Finding 1.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking | `KvCodec.java:52`, `KvStateMachine.java:215` | `KEY_PATTERN` has no length bound, but snapshot serialization uses `DataOutputStream.writeUTF`, which throws for strings whose UTF-8 form exceeds 65535 bytes. Probe: `PUT <70000-char-key> 1` passes the codec and commits (`OK`), after which **every** `takeSnapshot` on **every** node fails forever (`UTFDataFormatException: encoded string ... too long: 70000 bytes`) — the key can never be removed (no DELETE op), so with `purge.upto.snapshot.index` the log can never be purged again; a partial, unregistered `snapshot.<t>_<i>` file (no `.md5`) is left on disk each attempt; and the `SnapshotManagementApi` reply still reports `success=true`, so a client cannot even observe the failure. Non-blocking because every planned workload (DESIGN §2.5, M3) uses short fixed keys, so the harness cannot reach it; it should be fixed before any adversarial/fuzz workload exists. Cleanest fix: bound key length in `KvCodec` (e.g. reject keys > 1 KiB as `Malformed`). |
| 2 | non-blocking | `KvCodec.java:204` | Encode/decode asymmetry at the edge: `encodeReply(new Reply.Err(""))` produces `"ERR "`, which `decodeReply` rejects (`length > 4` guard). Unreachable today — every `Err` constructed in the codebase carries a non-empty reason — but a one-line guard (reject empty reasons at construction, or accept `"ERR "`) would close the gap. |
| 3 | non-blocking | `KvStateMachine.java:204-208` | `takeSnapshot` copies the whole map into a `TreeMap` while holding `applyLock`, blocking `applyTransaction` for the duration of the copy. Irrelevant at M0 scale (single-digit keys in tests, ≤ thousands in workloads) and it is the price of the atomic applied-index/state pair; worth revisiting only at the RocksDB stage. |
| 4 | non-blocking | `MiniCluster.java:201-219` | Ephemeral-port open-then-close allocation race, acknowledged in the worker's own Known gaps; a collision fails loudly (proven by the occupied-port experiment). Standard practice; fine. |

Observation for the record (no SUT defect): an earlier draft of my
log-ablation probe deleted logs while nodes held **unequal** snapshot
indexes — i.e. it destroyed committed entries that existed nowhere else.
That out-of-model fault (durable-state loss, not crash) produced a 60 s+
read outage in one run before I tightened the probe's precondition. Worth
remembering when M4 designs lazyfs lost-write scenarios: Ratis's behavior
under committed-state loss is exactly the kind of thing that milestone
will need deliberate scenarios (and expectations) for.

## Suggestions (non-blocking)

1. Bound key length in `KvCodec` (Finding 1) — a small follow-up job;
   pairs naturally with a `takeSnapshot` write-to-temp-then-rename to stop
   partial snapshot files, mirroring what the RocksDB stage will need
   anyway.
2. The worker's report suggests pinning the startup-line contract for
   Job 03 (`ratis-kv server started: id=... address=... storage=...
   group=... peers=...` on stdout after `RaftServer.start()`). Endorsed —
   my launcher runs confirm the line and its content; the coordinator
   should bless it in DESIGN before Job 03 writes the log-await.
3. The repo-root `./mvnw` spelling from the brief's acceptance command
   (worker deviation, reasonable under file ownership): a coordinator-side
   one-liner (symlink or wrapper copy) would make the brief's literal
   command work for M1 CI.
4. `SnapshotManagementApi.create` reporting success while `takeSnapshot`
   throws (observed in the long-key probe) is Ratis-side behavior worth a
   note in the M2 snapshot-churn nemesis design: snapshot success must be
   asserted from disk state (as the smoke test already does), never from
   the client reply.
