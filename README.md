# ratis-jepsen

[![jepsen](https://github.com/hooji/ratis-jepsen/actions/workflows/jepsen.yml/badge.svg)](https://github.com/hooji/ratis-jepsen/actions/workflows/jepsen.yml)

A from-scratch [Jepsen](https://github.com/jepsen-io/jepsen) test harness
for [Apache Ratis](https://ratis.apache.org/), the Java Raft library:
black-box correctness testing of a real replicated 5-node deployment under
fault injection, aimed at the defect classes Ratis's own tracker history
shows are real — snapshot install, restart/liveness, membership change,
read-index edges — plus storage durability and version skew.

Everything runs in Docker: one `control` container driving `n1..n7` db
nodes over SSH (Jepsen convention). The system under test is
`sut/ratis-kv`, a small KV server in this repository that embeds
`RaftServer`; the harness talks to it through the real `RaftClient`, so
the shipped client path (retry cache, `NotLeaderException` failover,
sliding window) is under test too.

**👉 The evidence is committed: [`results/`](results/README.md)** — 41
reference runs from 2026-08-07 against
[Ratis 3.2.2](results/2026-08-07-ratis-3.2.2/README.md) and the
[3.3.0 RC2 candidate](results/2026-08-07-ratis-3.3.0-rc2/README.md)
(checker verdicts, histories, evidence excerpts, most runs on public
CI with the job linked per row). Runs whose directories and table rows
say **`EXPECTED-RED`** fail *by design* — they are the proof the
harness convicts a lying system, not harness failures.

## Status

**M0–M5 complete** (jobs 01–12, each merged through an independent
adversarial review; the per-job record is in `jobs/` and `reviews/`).

| Milestone | What it added |
|---|---|
| M0 | 5-voter cluster, register workload, partition nemesis, per-key linearizability, seeded-bug red gate |
| M1 | crash-restart and pause nemeses, liveness checker, manual-dispatch CI |
| M2 | snapshot churn (real install-snapshot), leadership transfer, follower-served linearizable reads, membership churn + `--join` pool |
| M3 | exactly-once increment workload and counter checker; the retry-cache expiry boundary |
| M4 | storage-durability faults via [lazyfs](https://github.com/dsrhaslab/lazyfs) (un-synced-write loss, torn write) |
| M5 | version matrix (3.2.2 vs 3.3.0 RC2), mixed-version and rolling-upgrade topologies |

Every reference run — command, verdict, op counts, evidence excerpts —
is recorded in [`docs/RUNS.md`](docs/RUNS.md). Nothing in this README is
a claim the ledger does not back.

## What it tests, and how

**Workloads** (`--workload`):

- `register` (default) — read / write / compare-and-set over
  `jepsen.independent` keys, checked per key with knossos against a
  `cas-register` model.
- `counter` — deliberately retried, non-idempotent `ADD`s, checked
  against a per-key bounds model (`:ok` adds count exactly once, `:info`
  adds 0-or-1) with the observed total pinned at every apply. This is
  the exactly-once / retry-cache property.

Reads are linearizable (`raft.server.read.option=LINEARIZABLE`), sent to
the leader by default; `--reads follower|mixed` routes them at a
non-leader peer via `sendReadOnly(msg, peerId)`.

**Fault schedules** (`--nemesis`), one self-contained cycle each:

| Kind | What it does |
|---|---|
| `none` | fault-free baseline |
| `partition` | cycling random-halves network partition |
| `crash` | `kill -9` 1–2 of 5 (leader-biased), restart |
| `pause` | SIGSTOP/SIGCONT 1–2 of 5 |
| `quorum-pause` | SIGSTOP every follower, leave the leader appending but unable to commit |
| `snapshot-churn` | hold a follower back, transfer leadership, snapshot + purge, restart it — forces the real install-snapshot path |
| `transfer` | repeated leadership transfer |
| `membership` | add / remove / replace voters via `setConfiguration` over the 7-node pool |
| `membership-snapshot-churn` | both at once (fresh joiners must install a snapshot to join) |
| `listener-probe` | bounded probe: stage a LISTENER, promote, demote, remove |
| `unsync-drop` / `unsync-drop-all` | `kill -9` then drop un-fsynced page cache on a minority / on every voter (lazyfs) |
| `torn-write` | tear a log append mid-write, then restart over the torn store (lazyfs) |
| `rolling-upgrade` | roll every voter from OLD to NEW under load (needs `--mixed-version`) |
| `mixed` / `mixed-all` | whole fault segments drawn at random from the M1 three / from six kinds |

**Checkers.** Every run composes: the per-key linearizability (register)
or counter checker, a **liveness** checker (a healthy-majority cluster
that stops acknowledging for too long fails the run — Ratis's known
stuck-but-consistent bugs are invisible to linearizability alone,
nemesis windows are gated out), jepsen's stats/timeline/perf and
unhandled-exception surfacing, plus **evidence checkers** that fail a
run whose faults did not actually happen: install-snapshot events,
committed configuration transitions, joiner installs, lazyfs fault
acknowledgements, retry counts, and applied rolls. A green run that
never reached its fault path is not evidence, so the harness refuses to
call it one.

**Version dimension.** `--ratis-version V` selects the SUT tarball and
matches the harness's own `ratis-client` to it (`env/run.sh` overrides
the deps at launch; the harness refuses to start on skew).
`--mixed-version OLD,NEW` installs both on every node and either splits
the voters statically or rolls them one at a time.

**Durability dimension.** `--durability` (forced on by the lazyfs
nemeses) mounts each node's `/var/lib/ratis-kv` as a lazyfs FUSE mount
over `/var/lib/ratis-kv.root`, proving the mount per node and aborting
the run loudly if it cannot. Everything else is untouched when it is
off: no mount, no lazyfs process.

## Quickstart

Requires Docker with the compose plugin (and, for the durability
scenarios, an x86_64 host — see *Known limits*). From the repository
root:

```sh
env/run.sh up                                     # image + control & n1..n7, await ssh
env/run.sh test --nemesis partition --time-limit 300
env/run.sh down                                   # tear it all back down
```

`test` builds the SUT tarball on first use, installs it on the voter
nodes, runs the workload, and exits with the checker's verdict (0 =
pass); results land in `store/<test-name>/<timestamp>/`. Some more
shapes:

```sh
env/run.sh test --nemesis none --time-limit 120                  # fault-free baseline
env/run.sh test --workload counter --nemesis crash               # exactly-once under leader kills
env/run.sh test --nemesis membership --time-limit 300            # voter churn over the 7-node pool
env/run.sh test --nemesis unsync-drop --time-limit 300           # durability (lazyfs; x86_64)
env/run.sh test --nemesis partition --ratis-version 3.3.0        # a different Ratis release
env/run.sh test --nemesis rolling-upgrade --mixed-version 3.2.2,3.3.0
env/run.sh test --nemesis partition --seed-bug stale-reads       # must FAIL — the test of the test
env/run.sh probe --ratis-version 3.2.2                           # in-JVM library probe (see Findings)
```

`--seed-bug stale-reads` plants a deliberate SUT bug (linearizable reads
answered from a ~500 ms-lagging copy) that a correct harness must catch;
a run with that flag is expected to exit non-zero. `clojure -M:run test
--help` inside `control` lists every option; topology knobs (proxy CAs,
timeouts, extra Maven repositories) are in
[`env/README.md`](env/README.md).

## CI

The [`jepsen` workflow](.github/workflows/jepsen.yml) is
**manual-dispatch only** — no schedule, deliberately: the intent is to
offer this harness to the Apache Ratis project, whose maintainers would
then own the test cadence. A dispatch takes a comma-separated
`scenarios` list, a `time-limit`, a `ratis-version`, an optional
`mixed-version` pair and an optional `ratis-repo-url` (for release
candidates not yet on Maven Central). It builds the SUT tarball once per
distinct version, fans out one runner per scenario, and always uploads
each run's compressed `store/` as an artifact (7-day retention).

The default scenario list is
`none,partition,crash,pause,mixed,snapshot-churn,transfer,membership,membership-snapshot-churn,mixed-all,counter-crash`;
`counter-<kind>` tokens run the counter workload, `mv-<kind>` and
`rolling-upgrade` the mixed-version topologies, and the durability
tokens (`unsync-drop`, `unsync-drop-all`, `torn-write`,
`counter-unsync-drop`) are opt-in because building lazyfs costs every
runner ~2 minutes.

Every sweep also runs a **red-gate** job — a short seeded-bug run that
passes only if the harness *fails* it, with a `:valid? false` in a
`results.edn` as evidence — so each sweep re-proves the harness can
still catch bugs before its greens are believed.

## What it has demonstrated

Against **ratis 3.2.2** and, for the M5 suite, the **3.3.0 RC2**
artifacts (3.3.0 was not a completed release as of 2026-08-07; the
staged jars were byte-verified against the dev-area candidate):

- **Green where it should be green.** Register linearizability and
  liveness hold under partition, crash, pause, leadership transfer,
  snapshot churn with real install-snapshot events, membership churn
  with joiners installing snapshots to join, follower-served
  linearizable reads, and both static-mixed and rolling
  3.2.2 → 3.3.0 topologies.
- **Red where it should be red.** The seeded stale-reads SUT is
  convicted on all five keys under every nemesis it has been run with —
  the "test of the test", re-run in every CI sweep.
- **Exactly-once held** under leader-kill churn at the default 60 s
  retry-cache window, while the client demonstrably retried through the
  failovers. Past that window the documented double-apply is
  reproducible on demand (`--retry-cache-expiry-ms`), and the fault that
  produces it is timeout-shaped, not crash-shaped: `kill -9` at LAN
  latencies cannot reach it; a quorum freeze can.
- **Durability.** Every acknowledged write survived dropping un-synced
  page cache on a minority and on the whole cluster at once. A torn log
  append made the victim refuse to start
  (`CorruptionPolicy=EXCEPTION`, checksum mismatch, recorded verbatim)
  while the majority served the full op budget — loud refusal, no silent
  wrong data. A separate probe found `term`/`votedFor` written and
  fsynced before a node acts on a vote (source-proven at 3.2.2;
  probe-consistent).

Full tables, stores, and the preserved negative results (including the
runs that failed *correctly*) are in [`docs/RUNS.md`](docs/RUNS.md).

## Findings about Ratis

Classified in [`docs/BACKLOG.md`](docs/BACKLOG.md), which is
authoritative; summarized here so nobody has to guess how firm each one
is.

- **`BaseStateMachine.pause()` is a no-op that kills divisions on live
  snapshot install** (backlog 7) — chain verified from source and
  reproduced live at 3.2.2, and reproduced again at the 3.3.0 RC2
  artifacts with an in-JVM probe. An integrator using the shipped base
  class inherits it; our SUT manages the lifecycle itself, which is why
  its runs are green. Secondary and also present: the leader retries the
  install against the dead division with no backoff.
- **Staged LISTENER never leaves `STARTING`** (backlog 9) — every
  configuration mechanic (stage, promote, demote, remove) commits, but
  the division serves no client request, ever, because the caught-up
  mark in `checkStaging` is FOLLOWER-role-only. Mechanism pinned to a
  line; persists at RC2. This corroborates the open RATIS-1825 question
  with a mechanism and a candidate one-line fix.
- **`GroupInfoReply.getConf()` dropped by the wire serializer at 3.2.2**
  (backlog 8) — **already fixed** in the 3.3.0 RC2 artifacts. Not a
  defect to report upstream; the offer here is the test that keeps it
  fixed. Our log-census workaround is still needed for 3.2.2.
- **No parent-directory sync after the raft-meta rename** (backlog 10) —
  an open **question with a mechanism**, not a found defect. There is no
  double-vote defect at 3.2.2: term and vote are synced before the node
  acts. The narrow question is that POSIX does not make the rename
  itself durable without a directory fsync. **This harness cannot
  demonstrate it** (lazyfs passes renames through); it would need a
  dm-flakey/CrashMonkey-style follow-up.

No upstream issues have been filed against Ratis from this work. Two
bugs *were* filed against lazyfs
([#15](https://github.com/dsrhaslab/lazyfs/issues/15),
[#16](https://github.com/dsrhaslab/lazyfs/issues/16)) while building M4.

## Known limits

- **Checker.** knossos (`cas-register`) is the register checker; the
  elle migration is deferred, so per-key op budgets are capped in code
  to stay inside knossos's cost cliff. Analysis cost varies ~2× by host.
- **Scale.** Reference runs are 300 s, 5 keys, ~1500 ops (counter runs
  ~2100). This finds correctness bugs, not rare races that need hours.
- **Durability model.** lazyfs simulates lost un-synced writes and torn
  writes. It is not a power-loss rig: renames pass through, so the
  backlog-10 question is out of reach here.
- **Platform.** Everything is validated on x86_64 Linux. The image
  should build on arm64 (nothing else is arch-pinned) but has not been
  exercised there; the lazyfs build stage is amd64-only, so
  `--durability` runs fail loudly at mount proof elsewhere.
- **Metadata probe.** The `harness/scripts/metadata-probe.sh` decision
  rules need hardening before the experiment is quoted externally
  (backlog 11); until then its result is phrased "source-proven;
  probe-consistent", never "experimentally confirmed".
- **Scope.** Single Raft group, gRPC, no TLS, no DataStream API, no
  performance measurement, in-memory state machine with file snapshots
  (the RocksDB-checkpoint stage is not built).

## Pointers

| Path | What |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | purpose, decisions, milestones, open questions |
| [`docs/DESIGN.md`](docs/DESIGN.md) | the M0 design plus its dated amendments: SUT, harness, env, CI shape |
| [`docs/RUNS.md`](docs/RUNS.md) | reference-run ledger (every gate, green and red) |
| [`docs/BACKLOG.md`](docs/BACKLOG.md) | accepted findings, upstream candidates, deferred work |
| [`docs/PROCESS.md`](docs/PROCESS.md) | how this repo is built (coordinated jobs + adversarial reviews) |
| [`env/README.md`](env/README.md) | Docker topology, `run.sh`, knobs, lazyfs, deployment contract |
| [`harness/README.md`](harness/README.md) | the Clojure harness: namespaces, tests, how to run them |
| `sut/ratis-kv/` | the system under test: a KV server embedding `RaftServer` |
| `jobs/`, `reviews/` | the project's factual record — one brief + report per job, one independent review each |

## License

Apache-2.0. Source files carry the license header. The harness is written
from scratch; prior art (notably sofa-jraft-jepsen) was studied for shape
only, not copied.
