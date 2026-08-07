# ratis-jepsen — plan and open questions

*Drafted 2026-08-04. Status: pre-design. This document records the project's
purpose, the decisions already made, a proposed shape, and — the main payload —
the questions that must be answered before detailed design begins. Each open
question carries our current leaning so that "answering" it is a confirm/refute,
not a research project.*

## 1. Purpose

Build a Jepsen harness for **Apache Ratis** — black-box correctness testing of
a real replicated deployment under fault injection — targeting the defect
classes Ratis's own tracker history says are real, and validating the exact
properties the `statestore-replicated` (L3) provider will lean on.

Context this project inherits (evidence in
`RAFT_LIBRARY_EVALUATION.md`, 2026-08-02, in the parent workspace):

- Ratis was selected over SOFAJRaft as the L3 consensus library. Ratis has
  **no Jepsen, no TLA+, no durability harness of any kind**; the project's own
  RATIS-2542 (filed 2026-05-21) is an empty wishlist ticket asking for exactly
  this, naming `notifyInstallSnapshot` correctness and repliedIndex
  linearizability as desired targets.
- Ratis's historical correctness hot spots are **snapshot install**
  (RATIS-2500 infinite notification loop, RATIS-2147 MD5 mismatch, RATIS-2166),
  **restart/liveness** (RATIS-2523 stuck appender after follower restart,
  RATIS-2306 group metadata loss on fast restart), **membership change**
  (RATIS-1912 infinite election, RATIS-2274 stale conf), and
  **read-index edges** (RATIS-2350 read-after-write, RATIS-2511 follower read
  during install). A harness that doesn't reach these paths is testing the
  part of Raft that already works.
- The studied precedent is SOFAJRaft's setup: external Clojure harness
  (`sofastack/sofa-jraft-jepsen`), in-tree atomic-server SUT, CI workflow that
  builds the branch and injects its version into the harness, 5-node Docker
  topology, 6 nemeses, a mixed-version rolling-upgrade job, 90-minute GHA runs.
  We adopt the *shape*; per the from-scratch decision we write our own code.
- JRaft also supplies the cautionary lesson: its Jepsen ran green while an
  external TLA+ campaign found election-safety bugs rooted in fsync/ordering.
  Jepsen with `kill -9` does not test power loss; that class needs lazyfs (or
  similar) and is planned as a distinct milestone, not an afterthought.

## 2. Decisions already made (owner, 2026-08-04)

1. **Standalone repository** (this one), not inside the apache/ratis fork.
2. **From scratch** — our own implementation and practices; sofa-jraft-jepsen
   is prior art to learn from, not a codebase to copy.
3. **Private for now; no upstream engagement yet.** No RATIS-2542 comment, no
   PRs. After the prototype proves out, we decide next steps (stay internal,
   engage upstream, or donate). *(Updated 2026-08-04: repo public at M1 per
   Q7, and the intent is now settled — once we're happy with the harness,
   **offer this repo to the Apache Ratis project as a donation**; test
   cadence/CI scheduling are then theirs, which is why CI remains
   manual-dispatch-only meanwhile.)*
4. Deferred but kept open: the L3 end-state — pointing this harness (or its
   second SUT) at `statestore-replicated` so the same nemeses exercise our
   provider's semantics (CAS, exactly-once counters, fencing tokens, watches).
   The repo layout must not preclude a second SUT.

## 3. Goals and non-goals

**Goals (v1):**

- Linearizability checking of a keyed register workload (read / write / CAS)
  against a 5-node Ratis group, including **follower-served linearizable
  reads** (`sendReadOnly(msg, followerId)` under `read.option=LINEARIZABLE`).
- An **exactly-once increment workload** that deliberately retries
  non-idempotent increments through the client and checks a counter model —
  this validates the retry-cache property (`(ClientId, callId)` dedup rebuilt
  at apply on every replica) that the L3 counter design depends on.
- Nemeses: network partition (incl. majority/bridge), process pause
  (SIGSTOP), crash-restart (`kill -9` + restart), **membership churn**
  (add/remove/replace via `AdminApi.setConfiguration`, listener staging and
  promotion), **snapshot churn** (client-triggered snapshots +
  `purge.upto.snapshot.index=true` + a held-back follower, forcing the
  install-snapshot path repeatedly), leadership transfer.
- Version-parameterized SUT: one harness run tests an arbitrary released
  Ratis version or a locally built jar.
- Reproducible environment (containerized), runnable both on a dev machine
  and in CI (manual dispatch), with Jepsen's `store/` results kept as
  artifacts.

**Non-goals (v1, revisit later):**

- Performance measurement of any kind (evaluation stance: both libraries
  clear our envelope; benchmarks are noise here).
- lazyfs / power-loss simulation — **explicitly milestone M4, not v1**, but
  environment choices must not paint us out of it (FUSE availability).
- Mixed-version / rolling-upgrade topologies (M5).
- Multi-group Ratis, DataStream API, TLS, non-Linux platforms.
- TLA+/model checking (different tool, different project).
- Testing bare RocksDB behavior (the L2 provider has its own contract tests).

*(2026-08-07, Job 13: two of these non-goals have since been delivered
as their own milestones exactly as flagged — lazyfs/durability faults
at M4 (job 11) and mixed-version/rolling-upgrade topologies at M5
(job 12). The rest still stand: no performance measurement, no
multi-group/DataStream/TLS/non-Linux, no TLA+, no bare-RocksDB
testing.)*

## 4. Proposed shape (pending §6 answers)

```
ratis-jepsen/
├── docs/               # this plan; design docs to follow
├── sut/                # Maven module: the system under test
│   └── ratis-kv/       #   register/CAS server embedding RaftServer
├── harness/            # Clojure Jepsen project (deps.edn)
├── env/                # container topology (control + 5 db nodes)
└── .github/workflows/  # manual-dispatch run, artifact upload
```

- **SUT** (`sut/ratis-kv`): a standalone Java server embedding `RaftServer`
  with a keyed register state machine — `get`/`put`/`cas` commands, snapshot
  support from day one (snapshots are a primary test target, not an extra).
  Ratis version is a Maven property; the artifact is a tarball the harness
  installs on db nodes. Config profile: the evaluation's "minimal correct
  production set" (LINEARIZABLE reads, auto-snapshot enabled with an
  aggressively low threshold to force frequent install-snapshot, purge up to
  snapshot index, real election timeouts, real storage dir) — we test the
  configuration a competent operator would run, not the footgun defaults.
- **Harness** (`harness/`): Clojure, current Jepsen release. Client talks to
  the cluster via **direct Java interop on `RaftClient`** (leaning; Q1) —
  Ratis's fully relocated dependency tree makes its client a clean single
  Maven dep inside the Clojure JVM, and it means we test the client stack
  (retry cache, NotLeaderException failover, sliding window) that real
  consumers use. Outcome mapping lives in one namespace with an explicit
  exception→{:ok,:fail,:info} table — this is correctness-critical code and
  gets its own unit tests (the SPI v3 Transient/Permanent/Indeterminate
  taxonomy is the template).
- **Checkers**: per-key linearizable register (elle rw-register for scale;
  knossos on small smoke histories), counter model for the increment
  workload, plus a **liveness checker** — "functioning majority yet no acks
  for T seconds" flags a run, because Ratis's known bugs include stuck-but-
  not-inconsistent states (RATIS-2523, RATIS-2500) that pure linearizability
  checking would grade as merely slow.
- **Environment** (`env/`): Docker-based 5+1 topology in the Jepsen
  control-node-SSHes-to-db-nodes convention (leaning; Q7).

### Fault → known-defect-class targeting

| Nemesis / workload | Defect class it reaches (tracker precedent) |
|---|---|
| Snapshot churn + held-back follower | install-snapshot loops, digest/index bugs (RATIS-2500, -2147, -2166) |
| Crash-restart, incl. restart-on-idle and kill-right-after-join | stuck appender, group metadata loss (RATIS-2523, RATIS-2306) |
| Membership churn (incl. whole-set replace, listener promote) | election storms, stale conf (RATIS-1912, RATIS-2274, RATIS-1825's untrusted listener conversion) |
| Follower linearizable reads under partition/install | read-index edges (RATIS-2350, RATIS-2511) |
| Retried increments across leader kills | retry-cache/exactly-once (repliedIndex linearizability per RATIS-2542's own wishlist) |
| Partition/pause (stock) | baseline election + log safety |

## 5. Milestones (rough effort from the 2026-08-02 estimate)

*Status as of 2026-08-07: **M0–M5 all complete** — jobs 01–12, each
merged after an independent review. The list below is the original plan;
what each milestone actually delivered is in the job reports, and every
gate run is in `docs/RUNS.md`. Deviations worth knowing: M1 shipped the
liveness checker but not the elle migration (knossos remains the
register checker — BACKLOG 13), M3's Q14 run needed a new
`quorum-pause` nemesis to reach the expiry hazard, and M5 ran against
3.3.0 RC2 rather than a completed release.*

- **M0 — walking skeleton** (~1 wk): SUT server + tarball; harness brings up
  5 nodes, runs register workload with partition nemesis, knossos-checks a
  small history, locally in Docker. *Exit: one green run + one deliberately
  seeded SUT bug caught red.*
- **M1 — breadth + CI** (~1 wk): crash-restart + pause nemeses, elle
  checking, liveness checker, GHA manual dispatch with artifacts.
- **M2 — the Ratis-specific nemeses** (~1–1.5 wk): membership churn,
  snapshot churn, leadership transfer; follower-read workload.
- **M3 — exactly-once workload** (~3 d): increment workload + counter
  checker; retry-cache expiry edge runs (Q14).
- **M4 — durability class** (~1 wk): lazyfs on the log/metadata dirs;
  lost-write nemesis.
- **M5 — version matrix** (~3 d): run matrix over releases; mixed-version
  topology.

M0's seeded-bug gate is non-negotiable practice: a harness is only credible
once it has demonstrably caught a real injected violation (e.g., SUT briefly
serving non-linearizable reads from a follower with the read barrier disabled).

## 6. Open questions before detailed design

Grouped; each with current leaning. **Bold** = decisions that materially
constrain everything downstream and should be settled first.

### A. Client architecture

- **Q1 — Harness client: direct `RaftClient` interop or an HTTP/JSON shim in
  the SUT?** **Decided (owner, 2026-08-04): direct interop** — tests the
  shipped client path (retry cache, NotLeaderException failover, sliding
  window), zero shim code, relocated deps avoid classpath pain. Accepted
  cost: outcome mapping is against Ratis exception types, and the harness
  JVM shares fate with client bugs.
- **Q2 — Client identity model.** The retry cache keys on
  `(ClientId, callId)`. How do Jepsen worker processes map to `ClientId`s —
  one client per worker (natural; dedup scope per worker), or per-op clients
  (defeats dedup; wrong), or shared (contention, muddles callId streams)?
  Leaning: one `RaftClient` per Jepsen process, reconnect-on-crash keeps the
  same ClientId only if we persist it — decide whether client-restart
  simulation is in scope.
- **Q3 — Retry policy inside the harness.** Jepsen wants raw outcomes; the
  library's default is retry-forever. Originally decided: `noRetry()` for
  the register workload. **Amended 2026-08-05 (Review 05 discovery):
  bounded fixed-sleep same-callId retries** — `noRetry()` proved unsound,
  because NotLeaderException from a deposed leader is not proof of
  non-application (appended entries can commit under the successor), and
  grading it `:fail` produced a reproducible false-red on a healthy
  cluster. Same-callId retries are deduplicated by the server retry cache,
  so they convert that ambiguity into the cached true outcome; the
  exhausted residual is graded `:info`. The increment workload still gets
  its own client config (retry-cache behavior is its test subject).

### B. System under test

- **Q4 — State machine storage: in-memory or RocksDB?** **Decided (owner,
  2026-08-04): staged** — v1 = in-memory map + `FileListSnapshotInfo` file
  snapshots (isolates Ratis; snapshots still real files); a later milestone
  swaps in the RocksDB-checkpoint state machine, which doubles as the L3
  rehearsal and makes install-snapshot transfer
  multi-file/subdirectory-shaped like production.
- **Q5 — Bootstrap and membership superset.** **Decided (owner, 2026-08-04):
  pre-provisioned pool** — provision all N candidate nodes with
  `GroupManagementApi.add` and swing `setConfiguration` across them (env
  topology becomes an N≈7 pool with a 5-voter conf). Design the
  membership-churn nemesis explicitly as the rehearsal of the L3
  orchestrator's add-replace-dead-node flow, with reuse in mind.
- **Q6 — Read modes in scope.** Leader-linearizable + follower-linearizable
  in the main workload (leaning). Do we also run `sendReadOnlyNonLinearizable`
  / stale reads under a weaker (monotonic?) checker in v1, or defer? Leaning:
  defer; it dilutes v1 for a property we don't ship.

### C. Environment

- **Q7 — Docker vs LXC vs VMs; where do runs execute?** **Decided (owner,
  2026-08-04): prefer CI.** GitHub-hosted runners are the primary executor;
  local Docker is the dev loop, not the system of record. **The repo goes
  public just before the first CI runs are wired up** (owner, 2026-08-04 —
  i.e., at the start of M1), which makes hosted-runner minutes free from the
  first workflow run and removes private-tier metering entirely; M0 is
  built private/local. Shape: Docker compose topology
  (control + node pool), one **matrix job per nemesis scenario** (each
  scenario gets its own runner — parallel, isolated wall-clock; job cap 6 h
  vs. our 10–30 min scenarios); privileged containers for iptables
  partitions and SIGSTOP/kill (supported on hosted runners; proven at this
  exact topology by sofa-jraft's jepsen.yml, green runs 2026-06-15);
  checker memory (Q10 history budget) is the binding resource, not the
  cluster. Noisy shared-runner scheduling is mitigated by the production
  config profile's raised election timeouts (Q13). **Still open, gating M4
  only: the FUSE/`/dev/fuse`-for-lazyfs spike** in privileged containers on
  hosted runners; if it fails, M4 alone moves to a self-hosted/VM runner.
- **Q8 — Dev-vs-CI architecture split.** Dev machines are Apple-silicon
  (arm64), GHA is x86_64. Ratis is pure Java so both work, but container
  images and any natives (lazyfs) must be dual-arch or CI-only. Leaning:
  multi-arch images from day one; lazyfs x86-CI-only initially.
- **Q9 — SUT deployment convention.** Jepsen-standard control-node-SSH,
  tarball install, process supervision via jepsen's `db` protocol (leaning),
  vs. baking the SUT into node images (faster, less production-shaped).

### D. Checking and semantics

- **Q10 — Checker strategy and history budget.** elle rw-register as primary,
  knossos for smoke (leaning). Decide per-run op counts, key cardinality
  (independent keys), and time limits so checking cost stays sub-linear in
  run length; this bounds CI wall-clock.
- **Q11 — Is a liveness violation a run failure?** Leaning: yes, with a
  generous T (e.g., 60 s of majority-healthy no-progress) — RATIS-2523-class
  bugs are invisible otherwise. Needs careful nemesis-aware gating (don't
  flag during an active majority partition).
- **Q12 — Server-side evidence collection.** Scrape SUT logs for invariant
  tripwires (log corruption messages, `ExitUtils.terminate`, retry-cache
  metrics via `MetricRegistries.global()` reporter)? Leaning: collect
  logs + a metrics snapshot per run as artifacts in v1; assertions on them
  later.

### E. Ratis configuration under test

- **Q13 — Config profiles.** Primary = the evaluation's production profile
  (leaning). Do we *also* run a defaults profile to document footgun behavior
  (DEFAULT read option serving stale reads would fail linearizability —
  a known-non-bug that must be excluded or it's a standing false positive)?
  Leaning: no defaults profile; document why.
- **Q14 — Retry-cache expiry runs.** Default 60 s expiry vs. shrinking
  (`raft.server.retrycache.expirytime`) so expiry-window double-applies are
  *observable* within a 10-minute run. Leaning: dedicated M3 run with a
  short window — we *want* to see the documented failure mode demonstrated,
  because L3's Indeterminate-retry rule is calibrated to it.
- **Q15 — Pin the initial Ratis version.** 3.2.2 (our evaluated pin) vs
  jump to 3.3.0 (at RC2 as of 2026-07-30, likely released by build time).
  Leaning: start on 3.2.2 for continuity with the evaluation's line-level
  citations; add 3.3.0 to the matrix the week it ships.

### F. Project meta

- **Q16 — Clojure toolchain.** deps.edn + tools.build (leaning; modern) vs
  Leiningen (what most published harnesses and sofa-jraft-jepsen use, more
  copy-paste-able examples). Low stakes; pick once.
- **Q17 — License posture now.** Apache-2.0 LICENSE + headers from the first
  commit (leaning) — costs nothing, keeps the donate-to-ASF endgame clean,
  and avoids a provenance scrub later. Also: NOTICE crediting studied prior
  art even though no code is copied.
- **Q18 — Definition of green.** What a passing run asserts (linearizable +
  live + no tripwires), retention policy for `store/` artifacts, and whether
  we keep a `results/` ledger in-repo (leaning: no — artifacts in CI,
  summaries in docs).

### G. How the open questions actually resolved (amendment, 2026-08-07, Job 13)

The leanings above are preserved as written. Where the built repository
settled a question differently, or settled one the text left open, this
is the record:

- **Q6 (read modes)** — leader- *and* follower-linearizable reads both
  ship (`--reads leader|follower|mixed`, M2/job 07). Weaker read modes
  under a weaker checker stayed deferred, as the leaning said.
- **Q10 (checker strategy)** — **elle was not adopted**; knossos with a
  `cas-register` model is the register checker to this day, and the
  budget is enforced in code (`--key-count`, `--ops-per-key`,
  per-key concurrency). The migration is banked as BACKLOG 13 and would
  retire a real tuning class (analysis cost varies ~2× by host, and
  three runs have been pushed out of memory by `:info` mass — all
  preserved in `docs/RUNS.md`).
- **Q11 (liveness)** — answered yes, with nemesis-aware gating built
  from the nemesis's own fault→heal vocabulary (job 05).
- **Q12 (server-side evidence)** — went further than the leaning:
  per-run node logs are collected *and* asserted on. The evidence
  checkers fail a run whose fault did not demonstrably happen
  (install-snapshot events, committed conf transitions, joiner
  installs, lazyfs fault acks, applied rolls). Metrics snapshots were
  never wired up.
- **Q13 (config profiles)** — no defaults profile, as the leaning said.
  The one deviation is a deliberate test lever, not a profile:
  `--retry-cache-expiry-ms` (Q14).
- **Q14 (retry-cache expiry)** — demonstrated at M3, but only after
  three failed attempts: the hazard is **timeout-shaped, not
  crash-shaped**, and reaching it needed a new `quorum-pause` nemesis.
- **Q15 (initial version)** — 3.2.2 remains the default pin; 3.3.0
  joined the matrix at RC2 rather than at release.
- **Q18 (definition of green)** — a passing run asserts linearizability
  (or counter bounds) *and* liveness *and* the run's fault evidence,
  with no unhandled exceptions. `store/` artifacts are retained 7 days
  in CI; there is no in-repo `results/` ledger — summaries live in
  `docs/RUNS.md`, as the leaning proposed.

## 7. Immediate next steps

*(updated 2026-08-07 (docs refresh, Job 13): **M5 complete** — job 12
merged (version matrix 3.2.2 vs 3.3.0 RC2 + mixed-version and
rolling-upgrade topologies), which is the whole of M5 as scoped in §5;
Q15's "add 3.3.0 to the matrix the week it ships" is therefore
discharged early, against the release candidate rather than a
completed release — 3.3.0 was still under vote on 2026-08-07 and the
ledger labels it RC2 everywhere. All six milestones M0–M5 are now
merged. Two things §5 left implicit remain undone and unscheduled: the
elle migration (still knossos; BACKLOG 13) and the RocksDB state
machine (Q4's later stage). The upstream question of §2.3 is still
open for this harness's findings — none of BACKLOG 7–10 has been filed
against Ratis; the one Ratis filing so far (RATIS-2640 with its merged
fix apache/ratis#1543, §8) came from the preceding evaluation work,
not from the harness. Step 4 below is the next
decision, now with the working artifact it asked for.)*

*(updated 2026-08-06 (later): **M3 complete** — job 09 merged: exactly-once held under leader-kill churn at the default window; Q14 boundary demonstrated red only past it (quorum-pause nemesis; hazard is timeout-shaped, pre-step-down slice). **M4 complete** — job 11 merged: storage-durability faults
(minority/whole-cluster un-synced discard, torn write) all met their
stated expectations; term/votedFor proven durable-before-act at 3.2.2
(source-proven, probe-consistent; Review 11 planted a defect to test
the probe's power), with the parent-directory-rename question banked
as BACKLOG 10. **M5 underway**: job 12 version matrix (3.2.2 vs
3.3.0) + mixed-version topology. Earlier: **M4 gate cleared** (job 10 spike merged: lazyfs is CI-viable on
hosted runners; single-node clear-cache cannot strand acknowledged
writes, so M4 aims at torn writes and cluster-wide shapes).
**M4 underway**: job 11 durability faults. Earlier: **M2 complete** — job 07 (snapshot churn,
transfer, follower reads) and job 08 (membership churn, `--join`,
the `pause()` conviction + fix) merged; three upstream-report
candidates banked in BACKLOG 7–9; RUNS carries the Job-07
reinterpretation. **M3 underway**: job 09 exactly-once counter
workload. Earlier: **M1 complete** — jobs 05 (nemesis breadth +
liveness; incl. the Review-05-discovered outcome-map false-red, fixed
via ratified same-callId-retry amendment) and 06 (manual-dispatch CI
with red-gate, env polish, public README) merged; first full
five-scenario CI sweep dispatched on hosted runners. **M2 underway**:
job 07 snapshot churn + transfer + follower reads; job 08 membership
churn to follow. Earlier: **M0 complete** — jobs 01–04 merged through
the review protocol; both exit-gate runs reproduced independently by
review (green ×2 stable, seeded-red caught); repository flipped public. Earlier status: Q1/Q4/Q5 decided; Q7 settled — Docker 5+1,
dual-target dev/GHA — with only the FUSE-for-lazyfs spike left open,
gating M4 only.)*

1. Write `docs/DESIGN.md` for M0 against the settled answers: SUT command
   protocol, state-machine sketch, harness namespaces, env topology (N≈7
   pool, 5-voter conf), per-scenario CI matrix shape.
2. Build M0; demonstrate the seeded-bug catch.
3. Before M4 design: the 1-day FUSE-in-GHA spike (Q7c).
4. Then revisit this plan against reality and decide the upstream question
   with a working artifact in hand.

## 8. References

- `RAFT_LIBRARY_EVALUATION.md` (2026-08-02) — evaluation this project
  follows from; §1 correctness archaeology (defect classes), §2 testing
  culture, §8 spike traces (SUT embedding recipe with verified signatures).
- [RATIS-2542](https://issues.apache.org/jira/browse/RATIS-2542) — upstream's
  own (empty) distributed-testing wishlist; no engagement yet per §2.3.
- [RATIS-2640](https://issues.apache.org/jira/browse/RATIS-2640) /
  [apache/ratis#1543](https://github.com/apache/ratis/pull/1543) — our
  first upstream fix; evidence of contribution turnaround.
- [sofastack/sofa-jraft-jepsen](https://github.com/sofastack/sofa-jraft-jepsen)
  + sofa-jraft `.github/workflows/jepsen.yml` (merged 2026-06-15) — studied
  precedent for topology, nemesis set, CI shape. Ideas only; no code reuse.
- [Jepsen](https://github.com/jepsen-io/jepsen) — harness library;
  [elle](https://github.com/jepsen-io/elle) — checker;
  [lazyfs](https://github.com/dsrhaslab/lazyfs) — M4 lost-write simulation.
