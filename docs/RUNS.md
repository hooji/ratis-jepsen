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

## 2026-08-06 — M3 gates: the exactly-once counter workload (Job 09)

- **Commands**: `env/run.sh test --workload counter --nemesis <kind>
  --time-limit 300` (counter runs carry the sustained-stream defaults:
  rate 1.4, 800 ops/key); register regression:
  `env/run.sh test --nemesis crash --time-limit 300 --seed-bug stale-reads`
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv
  0.1.0-SNAPSHOT` (+ ADD + the expiry flag), JDK 21. All quoted gates
  ran on the final checker (per-key bounds + observed-total pinning).

| Run | Exit | Wall | Analysis | ok / fail / info | Retries (ops) | Store (`20260806T…`) |
|---|---|---|---|---|---|---|
| counter+crash #1 | 0 | 313 s | 1.5 s | 2095 / 1 / 45 | **286** (109) | `…counter-crash/114845.009Z` |
| counter+crash #2 | 0 | 319 s | 1.4 s | 2043 / 2 / 30 | **197** (71) | `…counter-crash/115408.681Z` |
| counter+mixed-all | 0 | 315 s | 1.7 s | 2098 / 1 / 6 | **122** (86) | `…counter-mixed-all/115934.890Z` |
| **Q14 red-by-design** | **1** | 313 s | 1.6 s | 1895 / 0 / 0 | **304** (120) | `…counter-quorum-pause/114319.903Z` |
| register + seed-bug (regression) | **1** | 315 s | ~1 s | — | — | `…register-crash-seedbug-stale-reads/111830.517Z` |

**The retry-cache-across-failover proof** (crash = leader-biased kill
cycles): both crash gates and the mixed-all gate held exactly-once on
every key — each `:ok` add counted exactly once (the checker
additionally pins the state at every single apply through the totals
`ADD` reports), each `:info` add 0-or-1 — while the client demonstrably
retried through the failovers: `:retry-evidence {:total 286, :ops 109,
:by-f {:add 257, :read 29}}` in crash #1, with the server retry cache
at its default 60 s window. `:info` sanity: the shakedown measured
70-of-71 write `:info`s inside kill windows; the gates' 45/30/6 follow
the same shape. Earlier same-day greens on the pre-strengthening
checker (`…counter-crash/105620.646Z`, `…110141.522Z`,
`…counter-mixed-all/110702.478Z`) are retained as history.

**Q14 (PLAN Q14 — the L3 Indeterminate-rule calibration), convicted on
all five keys.** Exact configs: `--workload counter --nemesis
quorum-pause --retry-cache-expiry-ms 500 --retry-delay-ms 5000 --rate 3
--ops-per-key 1200 --time-limit 300` — the server retry-cache window
shrunk to 500 ms (the flag's own contract says to keep it LONGER than
the client's total retry span; Q14 violates it on purpose) and the
client's inter-attempt delay raised to 5 s so retries overshoot it.
Verdict: `:valid? false` on **all five keys**, every violation
`:double-count`; the first, verbatim —

```
{:kind :double-count, :read {:final? false, :value 126}, :lower 121, :upper 121}
```

— a linearizable read of 126 where every exactly-once serialization
puts the counter at exactly 121 (the run has ZERO `:info` ops: no
0-or-1 slack, so every excess unit is a proven double-apply). Forensic
excess per key (max observed total − `:ok` sum, `:info` sum 0): +46,
+49, +40, +42, +49 — ≈226 double-applied delta mass in one 300 s run —
while the cluster acknowledged 1895 of 1895 ops as clean successes.
This is the silent over-count the L3 provider's Indeterminate-retry
rule exists to prevent: its calibration is now empirical, not
documentary.

**Getting the double required finding the right fault — three attempts,
all preserved:**

1. `--nemesis crash`, expiry 2000 ms / delay 3000 ms
   (`…counter-crash/111221.680Z`): green — every retry deduplicated.
2. `--nemesis crash`, expiry 500 ms / delay 5000 ms
   (`…counter-crash/112532.723Z`): green again; forensics showed max
   observed = `:ok` sum exactly on every key. Kill -9 cannot produce
   the needed applied-but-reply-lost population: append → replicate →
   commit → reply spans ~2 ms, so a killed leader takes its unreplied
   appends' replies down with the process only for the ops inside that
   sliver (~0.1 in flight at any instant).
3. `--nemesis pause` (minority SIGSTOP, expiry 500 / delay 5000,
   `…counter-pause/113333.439Z`): green — a paused LEADER is deposed
   ~2 s into the freeze and its unread requests never appended; only
   44 retries in the whole run.
4. The producer that works: **`--nemesis quorum-pause`** (new kind,
   Q14 lever) — SIGSTOP every follower, leave the leader alive: it
   keeps APPENDING client adds but cannot commit them, so every add in
   the stall window times out client-side (reply loss with surviving
   application, en masse), commits at resume, and its 5 s-delayed
   same-callId retry meets an expired cache entry and is appended
   AGAIN — `applyLogToStateMachine` at 3.2.2 applies every
   STATEMACHINELOGENTRY unconditionally (no apply-time dedup), so both
   copies apply. The negative results are calibration gold in their
   own right: **the documented expiry hazard is timeout-shaped, not
   crash-shaped** — process death at LAN latencies cannot reach it,
   sustained ambiguity (quorum loss, freezes, slow disks) can.

## 2026-08-07 — M4 gates: durability faults via lazyfs (Job 11)

- **Commands**: `env/run.sh test --nemesis <kind> --time-limit 300`
  (bare — the durability kinds force the lazyfs storage topology on and
  carry their own workload defaults); regression:
  `env/run.sh test --nemesis partition --time-limit 300` (durability
  off); counter gate: `--workload counter --nemesis unsync-drop`.
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv
  0.1.0-SNAPSHOT` (unchanged — no SUT edits this job), JDK 21, lazyfs
  `045a0b3a1126725e693934e29d3ba15e08cc39ec` baked into the env image.
- **Topology**: every voter's `/var/lib/ratis-kv` is a lazyfs FUSE
  mount over `/var/lib/ratis-kv.root`, proven per node at setup
  (mount-table type + fault fifo + fsync'd canary observed in the
  backing dir); un-synced page cache droppable per node on command.

| Run | Exit | Wall | Analysis | ok / fail / info | Fault evidence | Store (`20260807T…`) |
|---|---|---|---|---|---|---|
| durability topology, no faults | 0 | 52 s (30 s limit) | 0.5 s | 1093 / 407 / 0 | mounts proven ×5 | `…register-none-durability/095125.774Z` |
| unsync-drop | 0 | 318 s | 0.5 s | 1059 / 441 / 0 | 17 clear-cache acks / 10 cycles | `…register-unsync-drop/095257.921Z` |
| unsync-drop-all | 0 | 321 s | 8.2 s | 989 / 392 / 95 | 20 acks = 4 cycles × 5 nodes | `…register-unsync-drop-all/102348.341Z` |
| torn-write | 0 | 87 s total | 0.7 s | 1082 / 418 / 0 | 1 tear fired; victim **refused loudly**; majority served | `…register-torn-write/103745.994Z` |
| counter + unsync-drop | 0 | 315 s | 0.6 s | 2091 / 3 / 27 | 15 acks; 201 retries / 78 ops | `…counter-unsync-drop/103952.853Z` |
| partition regression (durability off) | 0 | 310 s | 0.8 s | 1078 / 406 / 16 | inert: no mount/daemon/log on any node | `…register-partition/104527.900Z` |
| **negative arm** (n3 lazyfs stubbed) | **255** | aborted in setup | — | — | `DURABILITY MOUNT UNPROVEN on n3 (:mount-await)` | `…register-none-durability/105338.963Z` (jepsen.log) |

**unsync-drop / unsync-drop-all (expectation GREEN — met):** kill -9
then `lazyfs::clear-cache` (power-loss ordering B) on a random minority
/ on every voter at once; every acknowledged write survived every drop
— linearizability, liveness (nemesis-gated windows) and the counter
bounds all `:valid? true`. The whole-cluster run recovers through a
full restart + election each cycle; its 95 `:info` ops are the honest
ambiguity of writes invoked during total outage.

**torn-write (expectation recover-or-refuse-loudly — met, refusal
arm):** lazyfs tore the victim's next log append mid-write (persisted
`16 bytes from offset 51333` of a ~48 B batch, then killed itself —
cache gone). On restart over the torn store, ratis-3.2.2 with the
default `CorruptionPolicy=EXCEPTION` **refused to start**:
`ChecksumException: Log entry corrupted: Calculated checksum is
0A482F49 but read checksum is 00000000` → `Failed to initRaftLog` (the
checksum bytes fell in the dropped tail; recorded verbatim in the
`:torn-restart` op and `n3/ratis-kv.log`). The 4/5 majority served the
full 1500-op budget linearizably throughout. No silent wrong data, no
lost acknowledged write. (The run ends ~55 s in: the torn script and
the op budget are both finite; the tear fired mid-write-stream at
t≈30 s.)

**Metadata-durability probe** (`harness/scripts/metadata-probe.sh`, 3
cycles, PASS): Ratis persists term/votedFor synchronously *before*
acting on a vote and fsyncs the file (source: `requestVote`/
`initElection` → `persistMetadata` → `AtomicFileOutputStream` with
`FileChannel.force(true)` before the rename). Experiment concurs: across
three forced elections the victim's `raft-meta` never diverged
mount-vs-backing in 609 samples at ~20 ms, and after kill +
cache-drop + restart its term never regressed below the term it voted
in (acted=2/3/4, recovered=2/3/4). Caveat for the record: the rename
itself is not followed by a parent-directory fsync — a real-power-loss
edge *outside* lazyfs's model (lazyfs passes renames through) —
details in the Job 11 report.

**Analysis-budget artifacts, preserved deliberately:** the first two
unsync-drop-all shapes OOMed knossos on every key
(`…unsync-drop-all/095908.783Z` — 8 windows, 135 `:info`;
`…101150.734Z` — 4 windows, 2 workers/key): a thread parks one
forever-concurrent `:info` write per ~5 s of total outage regardless of
rate. The shipped shape (calm 70 s / window 5 s / 10 keys × 1 worker)
keeps ~5–13 `:info`/key and analysis at 8.2 s. Also preserved:
`…torn-write/102955.099Z` — the first torn attempt, correctly FAILED by
the evidence checker (`torn-armed 1, torn-fired 0`): the fifo-armed
fault had been silently dropped by lazyfs's parser (it rejects
`occurrence=` for torn-op while still logging "configured
successfully"; two lazyfs bugs, documented in the report). The armed
form without `occurrence=` tears the next write, verified live.

**Costs** (4-core host; hosted runners are comparable): the lazyfs
image stage adds **1 m 54 s** to every cold image build (each CI runner
pays it); DB setup per durability run is **6–8 s** vs **4.7 s** plain —
the five 128 MiB cache pre-allocations run in parallel and cost ~2–3 s
total, far below the spike's 8 s/GiB-per-mount worry because the cache
is sized deliberately (see `db/lazyfs-cache-size`).

## 2026-08-07 — M5 gates: version matrix 3.2.2 vs 3.3.0 RC2, mixed-version topology (Job 12)

- **Commands**: `env/run.sh test --nemesis <kind> --time-limit 300
  --ratis-version <V>` (counter: `--workload counter`; listener probe:
  `--time-limit 180`; mixed rows: `--mixed-version 3.2.2,3.3.0`;
  probes: `env/run.sh probe --ratis-version <V>`). Every 3.3.0-bearing
  invocation ran with
  `RJ_RATIS_REPO_URL=https://repository.apache.org/content/repositories/orgapacheratis-1182/`.
- **Versions**: ratis 3.2.2 (Central) and **3.3.0 RC2** (the staging
  repo above; version string `3.3.0`), jepsen 0.3.13, SUT `ratis-kv
  0.1.0-SNAPSHOT` (no source change between versions — the SUT and its
  51-test suite build green at both), JDK 21. **3.3.0 is NOT a
  completed release as of 2026-08-07**: absent from Maven Central and
  `downloads.apache.org/ratis` (both fresh — checked against
  same-day artifacts elsewhere); present as `rc2` under
  `dist.apache.org/repos/dist/dev/ratis/3.3.0/` with the staging repo's
  `ratis-server-3.3.0.jar` byte-identical (sha512) to the jar inside
  the dev area's sha512-verified `apache-ratis-3.3.0-bin.tar.gz`. What
  we tested is exactly the bits under vote.
- **Harness client**: matched to the server under test per run by
  `env/run.sh` (`-Sdeps :override-deps`); recorded in each store as
  `:harness-ratis-client`; mixed runs run the OLD client (clients
  upgrade last). The in-harness skew guard was negatively tested: a
  deliberately mismatched launch (3.2.2 classpath, `--ratis-version
  3.3.0`) refuses with the `version skew` error before touching any
  node.

**Parameterization baseline (no regression)**: register + partition at
3.2.2 through the new plumbing — exit 0, 313 s wall, 1.2 s analysis,
1118 / 378 / 4 (ok/fail/info), store
`ratis-kv-register-partition-ratis-3.2.2/20260807T134626.186Z` —
matching its M0/M1 ledger shape (e.g. 2026-08-05 partition row
1089/408/3).

**The 3.3.0 suite — all eight runs green, no behavioral difference
surfaced** (exit 0 and every composed checker `:valid? true`
throughout; analysis 0.9–1.5 s per run; `:info` sanity spot-checked on
the counter run — 66 of 66 `:info` adds inside the ten kill windows
(+6 s completion tail); wall times below include node install/boot):

| Run @3.3.0 RC2 | Exit | Wall | ok / fail / info | Evidence | Store (`20260807T…`) |
|---|---|---|---|---|---|
| register + partition | 0 | 323 s | 1093 / 407 / 0 | — | `…partition-ratis-3.3.0/135646.129Z` |
| register + crash | 0 | 316 s | 1092 / 408 / 0 | — | `…crash-ratis-3.3.0/140159.982Z` |
| register + mixed-all | 0 | 316 s | 1104 / 396 / 0 | conf changes seen | `…mixed-all-ratis-3.3.0/140715.780Z` |
| counter + crash | 0 | 315 s | 1991 / 0 / 66 | retries 459 / 181 ops, exactly-once held | `…counter-crash-ratis-3.3.0/141232.015Z` |
| snapshot-churn | 0 | 313 s | 1116 / 384 / 0 | 1 install event (n5→n4, `followerNextIndex=878 < logStartIndex=1004`), receiver survived and served | `…snapshot-churn-ratis-3.3.0/141746.675Z` |
| membership | 0 | 322 s | 1097 / 403 / 0 | 21 committed conf transitions, 5 nodes through joins | `…membership-ratis-3.3.0/142300.702Z` |
| unsync-drop (lazyfs) | 0 | 318 s | 1117 / 383 / 0 | 14 clear-cache acks; mounts proven ×5 | `…unsync-drop-ratis-3.3.0/142822.894Z` |
| listener-probe | 0 | 76 s | 1112 / 388 / 0 | see BACKLOG 9 below | `…listener-probe-ratis-3.3.0/143341.391Z` |

**Candidate re-probes at 3.3.0 (BACKLOG 7–9)** — library probed
deliberately (`env/run.sh probe`: in-JVM RaftServers on a naive
`BaseStateMachine` subclass that does NOT manage the lifecycle — the
upstream-CounterStateMachine template our fixed SUT would mask):

1. **BACKLOG 7 — base-class lifecycle trap: PERSISTS at 3.3.0.**
   Probe (both versions, same code): `sm.pause()` on the naive SM
   leaves the lifecycle unchanged (`base-pause-reaches-paused=false`),
   and the full live install chain (stop follower → term bump →
   snapshot+purge → restart) still **kills the receiving division**:
   `install-outcome=died`, division CLOSED within 5 s of the install,
   SM never initialized, targeted reads refused — identical at 3.2.2
   (probe validity: reproduces the Job 08 conviction) and at 3.3.0.
   Source concurs: `BaseStateMachine.pause()` is byte-identical empty,
   `StateMachineUpdater.reload()` still asserts PAUSED, and the
   restructured 3.3.0 install path (`SnapshotInstallationHandler`
   append-to-temp + finalize) still calls `stateMachine.pause()` right
   before `reloadStateMachine`. The **install-retry no-backoff**
   secondary also persists: with the division dead, the leader logged
   90 `ServerNotReadyException` + 52 failed-append traces in a 30 s
   window at 3.2.2, and **186 + 100** at 3.3.0.
2. **BACKLOG 8 — `GroupInfoReply` conf dropped on the wire: FIXED at
   3.3.0.** One-call check over real gRPC: 3.2.2 `conf-present=false`
   (always `Optional.empty()`); 3.3.0 `conf-present=true` with the
   populated configuration (`peers { id: "p1" … startupRole: FOLLOWER }`).
   Source: 3.3.0's `ClientProtoUtils.toGroupInfoReplyProto` adds
   `reply.getConf().ifPresent(conf -> b.setConf(conf))`. Our
   upstream framing for this one flips to "already fixed in 3.3.0;
   here is the test that proves it stays fixed".
3. **BACKLOG 9 — staged LISTENER stuck in STARTING: PERSISTS at
   3.3.0.** Job 08's probe sequence re-run verbatim: stage n7 as
   listener (conf committed, replication to n7 confirmed from its own
   log at conf index 892), promote to voter (committed), demote,
   remove (all committed) — while a targeted linearizable read at n7
   fails `ServerNotReadyException: … is not in [RUNNING]: current
   state is STARTING` both as listener and ~12 s after promotion.
   Source concurs: `checkStaging` (the FOLLOWER-only
   `containsInConf(id)` caught-up mark) is byte-identical at 3.3.0.

**Mixed-version topology** (expectations committed in advance at
`687e4dc`; all met):

| Run (3.2.2 old / 3.3.0-RC2 new, client on old) | Exit | Wall | ok / fail / info | Store (`20260807T…`) |
|---|---|---|---|---|
| register + partition, static n1–n3 old / n4–n5 new | 0 | 318 s | 1079 / 417 / 4 | `…partition-mixed-3.2.2-3.3.0/143539.884Z` |
| register + crash, same static split | 0 | 317 s | 1119 / 370 / 11 | `…crash-mixed-3.2.2-3.3.0/144053.559Z` |
| rolling upgrade (all-old → roll n1…n5 to new under load) | 0 | 179 s | 1083 / 417 / 0 | `…rolling-upgrade-mixed-3.2.2-3.3.0/144611.090Z` |

The rolling run's evidence checker: **5/5 rolls applied, none failed,
none missing, zero skips** — each `:roll` op records
kill → symlink flip → restart → NEW startup line awaited, with the
version map walking `n1…n5` from all-`3.2.2` to all-`3.3.0` (op values
carry `:versions-now` at each step, e.g. after the third roll:
`{"n1" "3.3.0", "n2" "3.3.0", "n3" "3.3.0", "n4" "3.2.2", "n5"
"3.2.2"}`). Every 3.3.0 node opened its predecessor's 3.2.2-written
raft storage in place (RECOVER); linearizability and liveness held
through every intermediate mix, and the run's tail was the 3.2.2
client against an all-3.3.0 cluster. The shorter wall is benign: rolls
complete in seconds and no fault windows stretch op latencies, so the
1500-op budget (and the finite roll script) exhaust before the 300 s
limit. The **version-skew guard**'s negative arm also ran: launching
the harness with a 3.2.2 classpath but `--ratis-version 3.3.0`
refuses with `version skew: this JVM runs ratis-client 3.2.2 but the
run wants 3.3.0` before touching any node.

**Comparison table — scenario × version × outcome** (3.2.2 column =
this ledger's earlier gates + today's baseline; every cell a real run):

| Scenario | 3.2.2 | 3.3.0 RC2 | Mixed / rolling |
|---|---|---|---|
| register + partition | GREEN (M0 + today's baseline) | GREEN | GREEN (static mix) |
| register + crash | GREEN (M1) | GREEN | GREEN (static mix) |
| register + mixed-all | GREEN (M2) | GREEN | — |
| counter + crash (exactly-once) | GREEN (M3) | GREEN | — |
| snapshot-churn + install evidence | GREEN (M2, on the fixed SUT) | GREEN | — |
| membership + conf evidence | GREEN (M2) | GREEN | — |
| unsync-drop (lazyfs durability) | GREEN (M4) | GREEN | — |
| listener-probe (BACKLOG 9) | conf mechanics pass; **staged listener never serves** | **same wedge** | — |
| rolling upgrade 3.2.2→3.3.0 | — | — | GREEN, 5/5 rolled |
| Library probe: base pause()/install (BACKLOG 7) | division dies; no-backoff hammering | **division dies; no-backoff hammering** (persists) | — |
| Library probe: GroupInfoReply conf (BACKLOG 8) | dropped (empty) | **populated (fixed)** | — |
