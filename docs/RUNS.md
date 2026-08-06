# Run ledger

Reference runs, newest last. Summaries only — `store/` artifacts stay out
of git; each entry names the command that reproduces it.

## 2026-08-04 — M0 reference GREEN (Job 04 exit gate)

- **Command**: `env/run.sh up && env/run.sh test --nemesis partition --time-limit 300`
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21
- **Duration**: 313 s wall (≈25 s node install/boot, ops until the
  5×300-op budget exhausted, nemesis cycling to the time limit, 0.4 s
  knossos analysis)
- **Verdict**: `:valid? true`, exit 0 — "Everything looks good!"
- **Store**: `ratis-kv-register-partition/20260804T172534.329Z`

1500 ops over 5 independent keys checked in parallel (2 workers/key,
~10 ops/s each): 1072 `:ok`, 428 `:fail`, 0 `:info`. Every per-key
knossos cas-register analysis passed under a random-halves partition
cycling 15 s on / 15 s off. The fails are honest definite outcomes: CAS
precondition misses by design, plus a handful of `:not-leader-or-not-ready`
and `:read-index` transients around elections. In this run every partition
window that overlapped the op phase kept the leader on the majority side,
so no op ended ambiguous; two sibling runs of the same command (archived
`…T170652Z`, `…T171457Z`) caught leader-minority windows and show the
`:info` behavior — 60 and 71 write-path `:info` ops, 96–97 % inside or
within 5 s of a partition window, none in calm phases (the outliers sit
5.5–6.5 s after a heal, on still-recovering connections).

## 2026-08-04 — M0 reference RED (seeded bug caught)

- **Command**: `env/run.sh test --nemesis partition --time-limit 300 --seed-bug stale-reads`
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21
- **Duration**: 313 s wall (0.5 s analysis)
- **Verdict**: `:valid? false`, exit 1 — "Analysis invalid!", failures on
  **all five keys** (`:failures [0 1 2 3 4]`)
- **Store**: `ratis-kv-register-partition-seedbug-stale-reads/20260804T173140.098Z`

Same workload with every node started with `--seed-bug stale-reads`
(linearizable reads answered from a state copy that lags commits by
~500 ms; the nodes' logs carry the
`*** SEEDED BUG ACTIVE: stale-reads ***` banner). The checker convicts
within the first seconds of history. Key 0's violating pair, verbatim
from the analysis — one process wrote 2, then read the older 1:

```
{:op {:process 0, :type :ok, :f :write, :value 2, :index 228, :time 4337901282}}
{:op {:process 0, :type :ok, :f :read,  :value 1, :index 233, :time 4362177750},
 :model #knossos.model.Inconsistent{:msg "can't read 1 from register 2"}}
```

This is the M0 "test of the test": the harness demonstrably reports red
when the SUT lies.

## 2026-08-05 — M1 reference GREEN: crash ×2 (Job 05)

- **Command**: `env/run.sh up && env/run.sh test --nemesis crash --time-limit 300` (×2)
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21
- **Duration / analysis**: run 1: 328 s wall, 5.4 s analysis; run 2: 322 s wall, 2.1 s analysis
- **Verdict**: both `:valid? true`, exit 0 — "Everything looks good!"
- **Stores**: `ratis-kv-register-crash/20260805T044717.351Z`, `…/20260805T045245.825Z`

Ten crash cycles per run (calm 20 s → `kill -9` a random 1–2 of 5, leader
forced in with p=0.5 → dead 10 s → restart all): killed nodes report
`:started` on restart, the rest `:already-running`; the SUT recovers via
`StartupOption.RECOVER` and rejoins (re-election transitions in every
node log). Stats: run 1 = 1057 `:ok` / 408 `:fail` / 35 `:info`; run 2 =
1036 / 439 / 25. Outcome-mapping sanity: **all** 35 and 25 write-path
`:info` completions fall **inside** a kill window ([`:crash` invocation,
`:restart` completion]) — none adjacent, none in calm phases; reads have
zero `:info` (as the outcome map requires). The new liveness checker is
composed in and `:valid? true`, its calm regions tracking the fault
windows from the history's nemesis events (last cycle's kill at t≈295
stays an open window to history end — the time limit cuts before its
restart, and teardown cleans up).

## 2026-08-05 — M1 reference GREEN: pause (Job 05)

- **Command**: `env/run.sh test --nemesis pause --time-limit 300`
- **Duration / analysis**: 322 s wall, 0.7 s analysis
- **Verdict**: `:valid? true`, exit 0
- **Store**: `ratis-kv-register-pause/20260805T045807.172Z`

Ten SIGSTOP/SIGCONT cycles (running 25 s / stopped 5 s, random 1–2 of 5,
unbiased). 1098 `:ok` / 402 `:fail` / **0 `:info`** — every outcome
definite: pauses hit followers for the first eight cycles (boot leader n4
first entered the paused set near t≈250); the final leader-pause produced
a term-2 election (n5, t≈281) and the run's 10 `:not-leader-or-not-ready`
fails; the other 392 fails are designed CAS precondition misses.

## 2026-08-05 — M1 reference GREEN: mixed (Job 05)

- **Command**: `env/run.sh test --nemesis mixed --time-limit 300`
- **Duration / analysis**: 326 s wall, 1.7 s analysis
- **Verdict**: `:valid? true`, exit 0
- **Store**: `ratis-kv-register-mixed/20260805T050331.288Z`

Ten whole fault segments drawn uniformly at random — this run drew
5 partition / 4 pause / 1 crash (uniform draws, small n; the mix varies
per run). Faults never overlap by construction (each segment heals before
the next begins). 1068 `:ok` / 432 `:fail` / 0 `:info`; liveness
`:valid? true`.

## 2026-08-05 — M1 reference RED: seeded bug caught amid restarts (Job 05)

- **Command**: `env/run.sh test --nemesis crash --time-limit 300 --seed-bug stale-reads`
- **Duration / analysis**: 333 s wall, 0.7 s analysis
- **Verdict**: `:valid? false`, exit 1 — "Analysis invalid!", failures on
  **all five keys** (`:failures [0 1 2 3 4]`)
- **Store**: `ratis-kv-register-crash-seedbug-stale-reads/20260805T050901.890Z`

The M0 red-run lever under the M1 crash nemesis: every node runs
`--seed-bug stale-reads` (reads answered from a ~500 ms-lagging shadow
map), ten kill cycles land during the run (the last one's restart falls
past the time limit, as in the green runs), and restarted nodes
rejoin with the bug still active — the
`*** SEEDED BUG ACTIVE: stale-reads ***` banner reappears in every node's
log after every restart (the flag is plumbed through the same `db.clj`
start path the crash nemesis uses). The checker convicts anyway. Key 0's
violating pair, verbatim: process 0 wrote 2, then read the stale 3:

```
{:op {:process 0, :type :ok, :f :write, :value 2, :index 105, :time 4396595457}}
{:op {:process 0, :type :ok, :f :read,  :value 3, :index 116, :time 4527382074},
 :model #knossos.model.Inconsistent{:msg "can't read 3 from register 2"}}
```

(1022 `:ok` / 427 `:fail` / 51 `:info` — stale reads still *acknowledge*,
so the liveness checker rightly stays `:valid? true`; linearizability is
what convicts.)

## 2026-08-05 — M1 revision-1 gate re-runs (Job 05 / Review 05 amendment)

- **Code under test**: the ratified DESIGN §2.4 / PLAN Q3 amendment —
  bounded same-callId client retries (4 × 200 ms); write-path
  NotLeaderException / LeaderNotReadyException / null-cause
  RaftRetryFailureException graded `:info` (were `:fail`, which Review 05
  proved could false-red a healthy cluster: a deposed leader's appended
  writes commit under its successor; preserved review store
  `ratis-kv-register-mixed/20260805T061642.433Z`).
- **Command**: `env/run.sh test --nemesis <kind> --time-limit 300` per row
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21

| Run | Exit | Wall | Analysis | ok / fail / info | Store (`20260805T…`) |
|---|---|---|---|---|---|
| mixed #1 | 0 | 343 s | 0.5 s | 1105 / 381 / 14 | `…mixed/140406.555Z` |
| mixed #2 | 0 | 315 s | 0.5 s | 1086 / 404 / 10 | `…mixed/140929.564Z` |
| mixed #3 | 0 | 316 s | 0.3 s | 1084 / 416 / 0 | `…mixed/141444.860Z` |
| crash #1 | 0 | 317 s | 0.35 s | 1107 / 393 / 0 | `…crash/142001.226Z` |
| crash #2 | 0 | 317 s | 0.54 s | 1089 / 394 / 17 | `…crash/142517.701Z` |
| pause | 0 | 316 s | 0.3 s | 1082 / 415 / 3 | `…pause/143035.407Z` |
| partition | 0 | 315 s | 0.3 s | 1089 / 408 / 3 | `…partition/143551.251Z` |
| crash + seed-bug | **1** | 317 s | 0.4 s | 1110 / 380 / 10 | `…crash-seedbug-stale-reads/144105.762Z` |

All seven green runs `:valid? true` (liveness included); the seeded-red
still convicts on **all five keys** (key 3's pair: a committed
`cas [3 2]` at index 88, then a stale read of `1` at index 109 —
`can't read 1 from register 2`). `mixed` was run ×3 because the
false-red is intermittent (~1-in-3 in review); the three runs drew
4/4/2, 2/6/2 and 7/2/1 crash/pause/partition segments — 5 partition-heal
elections across them plus 10 more in the dedicated partition run, the
exact racing shape of the old defect, all green. The retries resolve
transients definitively: `:info` totals collapsed (44 across all seven
green runs vs 60 in the four pre-revision runs), every remaining `:info`
sits inside a fault window (crash #2: 17/17 inside; mixed #1: 14/14
inside), and knossos analysis stayed sub-second everywhere — no
regression toward the review's 4-core 20-minute outlier.

## 2026-08-05 — M2 part 1 gates: snapshot churn, transfer, follower reads (Job 07)

- **Commands**: `env/run.sh test --nemesis <kind> --time-limit 300` (+
  churn runs: `--rate 1.4 --ops-per-key 800` — the sustained write
  stream that crosses the server's purge.gap=1024 milestones; defaults
  unchanged)
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21

| Run | Exit | Wall | Analysis | ok / fail / info | Store (`20260805T…`) |
|---|---|---|---|---|---|
| snapshot-churn #1 | 0 | 318 s | 0.8 s | 1531 / 550 / 5 | `…snapshot-churn/172157.232Z` |
| snapshot-churn #2 | 0 | 320 s | 0.8 s | 1479 / 616 / 0 | `…snapshot-churn/180734.533Z` |
| transfer | 0 | 323 s | 0.5 s | 1088 / 408 / 4 | `…transfer/174240.178Z` |
| partition + `--reads mixed` | 0 | 317 s | 0.5 s | 1008 / 491 / 1 | `…partition/174801.489Z` |
| mixed-all | 0 | 321 s | 0.6 s | 1117 / 383 / 0 | `…mixed-all/175840.480Z` |
| snapshot-churn + seed-bug | **1** | 320 s | 1.0 s | 1492 / 527 / 97 | `…snapshot-churn-seedbug-stale-reads/175317.732Z` |

**Install-snapshot evidence** (the churn runs' reason to exist — counts
from the evidence checker, events = distinct leader-send/receive pairs):
run #1 total 4 = 2 events (`n4→n3` t≈140 s at term 6, `n5→n3` t≈285 s at
term 12); run #2 total 4 = 2 events (`n1→n3`, `n1→n5`). The first
event's pair, verbatim (run #1):

```
2026-08-05 17:24:17.537 [...] INFO ...GrpcLogAppender - n4@group-…->n3-GrpcLogAppender:
  followerNextIndex = 1133 but logStartIndex = 1151, send snapshot
  SingleFileSnapshotInfo(t:6, i:1192):[…/sm/snapshot.6_1192] to follower
2026-08-05 17:24:17.584 [...] INFO ...SnapshotInstallationHandler - n3@group-…:
  receive installSnapshot: n4->n3#0-t6,chunk:3e1b2d99-44ea-4910-a9c7-9db55c769bee,0
```

Outcome-mapping sanity (churn #1): all 5 `:info` completions inside
`:churn-kill`→`:churn-restart` windows; churn #2 has zero `:info` at
all. The `mixed-all` run drew 1 churn / 1 crash / 4 pause / 3 partition
/ 1 transfer segments (evidence not required there — its churn share
sits below the purge gap by design; counts still reported). The
follower-reads run sent 234 linearizable reads follower-targeted
(`:read-via` spread n1:52 n2:36 n3:61 n4:60 n5:25) under the partition
cycle and stayed linearizable. The seeded-red run convicts **all five
keys** amid full churn — 2 install events landed during the seeded run
itself — key 0's pair: wrote 0 (index 207), read stale 3 (index 213),
`can't read 3 from register 0`.

**Two first-attempt artifacts, preserved deliberately:**

1. `…snapshot-churn/165902.318Z` — the brief's original cycle (no
   in-cycle transfer): three churn cycles, snapshot replies
   `:success? true`, **zero installs** — the evidence checker failed the
   run (exit 1, `:error :no-install-snapshot-evidence`). This is the
   checker's negative proof on a real cluster, and the demonstration
   that kill+snapshot+restart alone cannot reach install-snapshot at
   default segment/purge settings (see the Job 07 report's mechanism
   triage).
2. `…snapshot-churn/172714.309Z` — churn #2's first attempt: exit 2,
   `:valid? :unknown`, 924 s wall — 147 `LeaderSteppingDownException`
   completions (no outcome row before this job; pessimistic `:info`)
   pushed knossos out of memory on key 0. Triaged to the pre-append
   admission check at 3.2.2; the new `:leader-stepping-down` definite
   `:fail` row eliminates the class (run #2's re-run: 0 `:info`,
   0.8 s analysis).

## 2026-08-05 — M2 part 2 gates: membership churn (Job 08)

- **Commands**: `env/run.sh test --nemesis <kind> --time-limit 300`
  (probe: `--time-limit 180`; the combined kind carries its own
  workload defaults, rate 1.4 / 800 ops-per-key — no extra flags)
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv
  0.1.0-SNAPSHOT` **with the Job 08 state-machine lifecycle fix**
  (below), JDK 21. Membership-bearing kinds run all 7 nodes.

| Run | Exit | Wall | Analysis | ok / fail / info | Conf transitions | Store (`20260805T…`) |
|---|---|---|---|---|---|---|
| membership #1 | 0 | 316 s | 2.5 s | 1081 / 419 / 0 | 21 | `…membership/221237.705Z` |
| membership #2 | 0 | 334 s | 1.6 s | 1106 / 394 / 0 | 21 | `…membership/221817.914Z` |
| membership-snapshot-churn | 0 | 321 s | 1.9 s | 1480 / 598 / 6 | 8 | `…membership-snapshot-churn/222358.282Z` |
| mixed-all | 0 | 313 s | 1.7 s | 1117 / 383 / 0 | 3 | `…mixed-all/222925.746Z` |
| listener-probe | 0 | 74 s | 1.5 s | 1099 / 401 / 0 | 4 | `…listener-probe/223445.323Z` |
| membership + seed-bug | **1** | 313 s | 2.2 s | 1131 / 369 / 0 | 20 | `…membership-seedbug-stale-reads/223604.824Z` |

**Membership evidence** (the law, extended to conf changes): both
dedicated runs committed 21 distinct transitional conf entries each —
per-move mix #1: 5 add / 6 remove / 3 replace committed (2 replaces'
client replies exhausted; the heal half's census-retry reconciled) —
and six distinct nodes cycled through joins in each
(`:joined [n5 n6 n4 n7 n3 n2]`). The first transitional line, verbatim
(membership #1 — the remove of n5 committing at index 774; only
`old=peers:` lines count as evidence — every new leader re-appends a
*stable* conf at its startup index, so elections never masquerade as
conf changes):

```
2026-08-05 22:13:06.462 [grpc-default-executor-9] INFO org.apache.ratis.server.RaftServer$Division -
  n1@group-ABBC16E54704: set configuration conf: {index: 774, cur=peers:[n1|n1:6000, n2|n2:6000,
  n3|n3:6000, n4|n4:6000]|listeners:[], old=peers:[n1|n1:6000, n2|n2:6000, n3|n3:6000, n4|n4:6000,
  n5|n5:6000]|listeners:[]}
```

**Joiner-install evidence** (combined run): 4 nodes joined; the one
pre-first-snapshot join (n6) correctly needed no install; **all three
post-snapshot joiners installed during staging**
(`:joined-with-installs ["n2" "n4" "n5"]`), 5 clean installs total,
0 staging failures. n5's pair, verbatim — a fresh joiner (log from 0)
behind a purged leader log, install as the only way in:

```
2026-08-05 22:28:35.634 [...] INFO ...GrpcLogAppender - n4@group-…->n5-GrpcLogAppender:
  followerNextIndex = 0 but logStartIndex = 2219, send snapshot ... to follower
2026-08-05 22:28:35.672 [...] INFO ...SnapshotInstallationHandler - n5@group-…:
  receive installSnapshot: n4->n5#0-t9,chunk:e69da59a-…
```

The seeded-red run convicts **all five keys** (`:failures [0 1 2 3 4]`)
while 20 conf transitions and 5 joins churn around it; its membership
evidence stays green — linearizability is what convicts. `:info`
sanity: zero `:info` in every run except the combined (6, all inside
churn/replace fault windows).

**Conviction, preserved (the reason the SUT fix exists):**
`…membership-snapshot-churn/214003.673Z` — the first combined gate,
run on the pre-fix SUT, exit 1 with `:error
:no-joiner-install-evidence`: three nodes joined, none could keep an
installed snapshot — at ratis-3.2.2 `BaseStateMachine.pause()` is
empty, `StateMachineUpdater.reload()` asserts the PAUSED lifecycle
state, and the assert failure closes the whole division. Both install
receivers in that store crashed identically
(`IllegalStateException at StateMachineUpdater.reload:230`, division
`shutdown` ~25 ms after `successfully install the entire snapshot`),
the staged joiner's `setConfiguration` wedged ~60 s in NOPROGRESS
staging while rejecting all other conf changes and transfers. Triage,
fix and upstream-report framing in `jobs/08-membership-churn/08_report.md`.
Also preserved from the same pre-fix pass: `…membership/211831.264Z`
(the zero-add uniform-draw shakedown that motivated block draws — green,
10 transitions), `…membership/212908.588Z`, `…membership/213435.123Z`
(pre-fix membership gates, 20/21 transitions, green).

**Listener-staging probe** (bounded; RATIS-1825 territory) — ran
twice, identical outcome, both preserved
(`…listener-probe/215054.707Z`, `…223445.323Z`): every conf mechanic
**passes** — stage n7 as LISTENER, replication to it confirmed from
its own log (conf index 845 adopted), **promote listener→voter
commits**, demote commits, remove commits, pool restored. The wedge:
n7's division never leaves lifecycle STARTING — a linearizable read
targeted at it fails `ServerNotReadyException: … is not in [RUNNING]:
current state is STARTING` both as listener AND ~15 s after promotion
to voter, while it replicates the whole time. Mechanism pinned at
3.2.2: `checkStaging` marks staged peers caught-up through a
FOLLOWER-role-only `containsInConf`, so a staged listener is never
marked; the leader's appends to it stay `initializing=true`, and
`RaftServerImpl` (line 1611) only transitions STARTING→RUNNING on a
non-initializing append. Client-facing availability of a staged
listener is therefore zero until a process restart. Details and
upstream framing in the Job 08 report.

## 2026-08-06 — Coordinator correction: reinterpretation of the Job 07 green runs

Review 08 established (reproduced live, store
`ratis-kv-register-snapshot-churn/20260806T064704.897Z` in the reviewer
environment) that the Job 07 snapshot-churn entries above ran against a
SUT whose `pause()` lifecycle bug (BACKLOG item 7) killed each churned
follower's division ~4 ms after every successful live install. Those
runs' verdicts stand — installs were durable, linearizability held, the
evidence counted real events — but their implied "follower recovered
via install-snapshot and kept serving" reading was wrong: recovery
came from RECOVER restarts in later cycles, and "the retry storm" was
the leader hammering a division its own install had killed. Fixed in
Job 08 (`KvStateMachine` lifecycle discipline); the combined M2 gates
below ran on the fixed SUT with joiners serving reads after install.
