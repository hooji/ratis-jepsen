# Review 09 report — M3: the exactly-once increment workload

## Verdict: MERGE

## Justification

Every acceptance criterion reproduced independently: both suites, the
crash ×2 / mixed-all greens with nonzero retry counts, the Q14
conviction (proven double mass beyond slack on all five keys, zero
`:info`), the register regression, and — the review brief's emphasis 4 —
**both complementary boundary probes green**, plus a third
window-position run of my own that locates the boundary exactly. The
custom bounds checker survived a nine-fixture adversarial attack
including shapes of my own design; the ADD pinning is a true
linearization witness (computed under the SUT's apply lock on the
serial updater thread, verified in source); the retry-observer's live
counts obey arithmetic identities they could not satisfy if it over- or
under-counted. One finding of substance came out of the boundary
forensics — the stalled leader **steps down mid-stall**
(`LOST_MAJORITY_HEARTBEATS`), which refines the worker's "keeps
appending the whole window" narrative and matters for the L3 design
record — but it does not change the conviction, the calibration's
direction, or any verdict. Non-blocking.

## What I verified

Environment: the Docker 8-container topology, ratis 3.2.2, jepsen
0.3.13, JDK 21; worker branch `claude/membership-churn-analysis-nn6hlo`
at 0fb8802 in a read-only worktree; ratis sources from the Maven
Central `-sources` jars.

### Emphasis 1 — attacking the bounds checker

**Pinning design, verified at the SUT**: `applyTransaction` computes
the reply inside `synchronized (applyLock)` together with the map
mutation (`applyWrite` → `merge(key, delta, Long::sum)` →
`Reply.Val(updated)`) and `updateLastAppliedTermIndex`, on the strictly
serial StateMachineUpdater thread — the reported total is the state at
this op's position in the total apply order, atomically captured. A
true linearization witness. The harness keeps the delta in `:value`
and rides the total on `:observed`; the checker bounds-checks the
**pre-state** (total − own delta) over the op's own interval with the
op excluded from its own bounds — sound, because a deduplicated
retry's cached original total only ever *widens* `upper` via the late
completion (verified adversarially below).

**The attack**: nine fabricated histories driven through
`check-counter-key` directly (script preserved in my session notes;
run against the worktree read-only). All nine judged correctly:

| Fixture | Expectation | Result |
|---|---|---|
| A1 dedup-cached-original with late completion (my false-positive trap: tight-but-legal on every bound) | no conviction | ✓ valid |
| A2a (brief's shape) `:info` add applied once, visible through a later add's pinned pre-state | no conviction | ✓ valid |
| A2b same `:info` add applied twice | convict | ✓ `:double-count` on both the read and the pinned pre-state |
| A3 (brief's shape) concurrent adds, read interleaved mid-apply | no conviction | ✓ valid |
| A4 cross-op reply swap (two `:ok` adds share a total) | convict | ✓ `:duplicate-observed-value` — and independently `:double-count` on the pinned pre-state (two grounds; stricter than my expectation) |
| A5 a `:fail` add that in reality applied | convict | ✓ `:double-count` — the ":fail must be exactly zero" property demonstrably turns outcome-map unsoundness into conviction |
| A6a pending add applied once | no conviction | ✓ valid |
| A6b pending add applied twice | convict | ✓ `:double-count` |
| A7 lost `:ok` add | convict | ✓ `:lost-update` |

On the deliberate `:fail`-exactly-zero property: A5 proves the
mechanism (a mis-graded definite-fail convicts), and the live
certification in my reproduction comes from the **mixed-all** gate —
its 4 `:fail` ops are all adds, and every key stayed green within
bounds that *exclude* them. My two crash gates happened to produce
zero `:fail` adds (their 3 fails were reads), so the crash-specific
leg of "green gates certify the definite-fail rows" rests, in my runs,
on mixed-all (which includes crash segments) plus fixture A5; the
worker's own crash gates recorded 1 and 2 fails. The property and its
certification mechanism hold; the crash-only sample is just small.

### Emphasis 2 — the exactly-once greens, re-run

| Run | Exit | ok / fail / info | Retries (ops) | Store (`20260806T…`) |
|---|---|---|---|---|
| counter+crash #1 | 0 | 2050 / 0 / 37 | **257** (95) | `…counter-crash/150532.962Z` |
| counter+crash #2 | 0 | 1981 / 3 / 47 | **386** (158) | `…counter-crash/151053.229Z` |
| counter+mixed-all | 0 | 2021 / 4 / 4 | **110** (80) | `…counter-mixed-all/151610.211Z` |
| register + seed-bug (regression) | **1** | — | — | `…register-crash-seedbug-stale-reads/154320.175Z`, `:failures [0 1 2 3 4]` |

Exactly-once held through leader-biased kills at the default 60 s
window with hundreds of live retries; the register seeded-red still
convicts all five keys. Zero-retry negative arm: the unit fixture
(`retry-verdict-decision`) — a required run with `:total 0` fails
`:no-retry-evidence` — re-ran green in the suite, as the brief allows.
Suites: SUT **51 tests** green (mvnw verify; the expiry flag logs
`retrycache.expirytime = 2000ms (custom)` under test); harness **96
tests / 898 assertions** green — matching the PR body; the report's
"93/891" is the stale pre-final-commit number (documentation nit,
finding 4). `:info` sanity: crash #1's 37 `:info` all cluster inside
its kill windows (verified by history position), calm phases clean.
Analysis times: 0.26–1.3 s across all nine of my counter runs (the
Q14 red, with its ~190 violations to report, is the 1.3 s worst case)
— the checker's quadratic worst case stays far from any cliff.

### Emphasis 3 — the Q14 conviction, its causation, and the analysis

**Reproduced** with the ledger's exact configs (`--nemesis quorum-pause
--retry-cache-expiry-ms 500 --retry-delay-ms 5000 --rate 3
--ops-per-key 1200`): exit 1, convictions on **all five keys**, first
violation verbatim `{:kind :double-count, :read {:final? false,
:value 116}, :lower 111, :upper 111}` — a linearizable read five above
the exact bound. **Zero `:info` ops** (no 0-or-1 slack): per-key
high-water excess over the `:ok` sum = +37/+36/+44/+39/+35 ≈ **191
proven double-applied delta mass** behind 1890 clean `:ok`s
(store `…counter-quorum-pause/152215.612Z`).

**Causation is a controlled experiment, not inference**: my boundary-A
run (below) is the *same* schedule, same 5 s delay, same rate/budget,
differing **only** in the expiry window (60 s default vs 500 ms) — and
it is not just green but *exactly clean*: per-key high-water equals the
`:ok` sum to the unit on all five keys, with 303 retries on 120 ops all
deduplicated. Window in, doubles out; window out, doubles in. The
server-side mechanism is source-verified at 3.2.2: the retry cache is
Guava `expireAfterWrite` (`RetryCacheImpl:182–184`), entries are
(re)written at **apply** on every replica
(`replyPendingRequest`:1807/1831, called from `applyLogToStateMachine`),
`queryCache` treats an expired-entry retry as a brand-new request
(`newEntry.isInitialized()` → not-a-retry) even while the original is
pending, and `applyLogToStateMachine` applies every
STATEMACHINELOGENTRY unconditionally — no apply-time dedup. Every link
of the claimed mechanism holds.

**The timeout-shaped-not-crash-shaped analysis** — checked against
source and my own stores:

- *"~2 ms append-to-reply span; kill -9 can't strand
  applied-unreplied ops in meaningful numbers"*: mechanically sound
  (the strandable population is ops inside the append→commit→reply
  pipeline at the kill instant ≈ in-flight × pipeline-span; at these
  rates ≪ 1 per kill), and consistent with my crash gates: 384 retried
  ops across two crash runs at the default window produced zero
  bounds violations — the retry-cache-across-failover behavior the
  runs exist to prove.
- *"A frozen leader is deposed before its unread requests append"*
  (the minority-pause negative): the load-bearing invariant is
  actually slightly stronger than the phrasing — a resumed old-term
  leader may well *append* buffered requests, but it cannot *commit*
  them (followers at the higher term reject; the successor's log
  wins), so the applied-but-reply-lost population still cannot form.
  The conclusion stands on commit rules, not on race timing.
- **One real imprecision found** (finding 1): the quorum-pause
  narrative — "the live leader keeps accepting and APPENDING client
  writes" for the whole window — is contradicted by the stores: the
  stalled leader **steps down mid-stall** with
  `StepDownReason:LOST_MAJORITY_HEARTBEATS` (observed ~once per cycle,
  10 step-downs in my boundary-B store), so only the first ~2–4 s of
  each window's adds append; the remainder are refused
  NotLeaderException by the deposed leader. The hazard population is
  the *pre-step-down slice*, not the whole window — which my per-stall
  double counts corroborate (~8 doubled ops per stall ≈
  one-in-flight-per-worker over the slice). The conviction and the
  calibration's direction are unaffected — but the L3 design record
  should carry the corrected mechanism (and its corollary: Ratis's own
  step-down bounds the hazard window to roughly the
  leader-step-down latency, which is itself useful calibration data).

**Liveness gating**: `:liveness {:valid? true}` on all four of my
quorum-pause stores — the deliberate majority-loss windows are
correctly fenced by the `:quorum-pause`→`:quorum-resume` fault pair;
no false stall flags anywhere.

### Emphasis 4 — boundary honesty, both sides

| Run (all quorum-pause, rate 3, 1200 ops/key, 300 s) | Window | Delay | Exit | Per-key high-water − okSum | Verdict |
|---|---|---|---|---|---|
| Q14 red | 500 ms | 5000 ms | **1** | +35…+44 with **zero** `:info` slack — proven doubles | RED, correctly |
| boundary (a) | 60 s default | 5000 ms | 0 | **0 on every key** (exactly clean; 303 retries all deduped) | GREEN |
| boundary (b) | 500 ms | 200 ms | 0 | inside the `:info` allowance | GREEN |
| boundary (b′), mine | 4000 ms | 200 ms | 0 | inside the `:info` allowance | GREEN |

Both brief-mandated probes green: red only past the boundary — a
calibration, not a scare story. Two review-grade observations on (b),
which I ran to adjudicate my own pre-registered prediction that it
would be red (the 3 s per-attempt rpc timeout dominates the 200 ms
sleep, so the first retry arrives ~3.2 s after the entry write —
outside any 500 ms window):

1. It stays green for a *mechanism* reason, not an arithmetic one: the
   mid-stall step-down (finding 1) makes post-step-down attempts fail
   fast with NotLeaderException, the stock 5 s invocation deadline then
   axes the stalled ops as `:info` (367 of them, `:retries 4` — full
   exhaustion) before any post-resume retry exists to meet an expired
   entry; and with no leader mid-stall there is nothing to re-append
   copies. The green is genuine.
2. But it is *evidentially weaker* than (a)'s green: 367 `:info` adds
   put ~200+ delta units of 0-or-1 allowance per key between the
   high-water and the conviction threshold, and my forensics can place
   the observed state only *inside* that allowance (B and my B′ are
   indistinguishable on this metric). The clean two-sided calibration
   is therefore **(a) vs Q14** — same delay, window on either side of
   the total attempt span, exactly-clean vs proven-doubles — with (b)
   as a supporting green whose margin rides the `:info` allowance. The
   boundary is window vs **total client retry span** (per-attempt rpc
   timeout + sleep, times attempts) — the worker's own formulation in
   the SUT flag docs ("longer than total client retry") is the correct
   one; a delay-only reading of the boundary would not survive (b)'s
   internals.

### Emphasis 5 — the observing RetryPolicy wrapper

- **Delegation is complete by construction**: `RetryPolicy` at 3.2.2 is
  a `@FunctionalInterface` with exactly one abstract method
  (`handleAttemptFailure`); the wrapper reifies that method, bumps an
  atom, and returns the inner policy's `Action` unchanged. There is no
  other decision path to alter. Thread-safe (atom).
- **Independent live verification by arithmetic identity** (client
  debug logs are DEBUG-only at 3.2.2, so I used the stronger check):
  in crash #1, retries distribute 22×1 + 21×2 + 15×3 + 37×4; the sum
  reconciles to the reported total (257) and op count (95) exactly, and
  **every `:info` op carries `:retries` exactly 4** — the
  source-derived exhaustion count for
  `retryUpToMaximumCountWithFixedSleep(4, …)` (`RetryLimited`: retry
  while `attemptCount < 4`), with no op above 4 in any crash store.
  Over- or under-counting would break these identities. (The Q14
  stores' small `:retries 5` tail (10 ops) rides the harness-timeout
  cancellation path — an interrupted attempt's failure is still
  consulted; consistent with counting *consultations*, which is what
  the ledger says it counts.)

### Emphasis 6 — diffs

- **SUT**: exactly the two itemized pieces. ADD = codec form + one
  apply case + read-path guard (~25 lines); expiry flag = parse +
  validation + one conditional `RaftServerConfigKeys.RetryCache
  .setExpiryTime` (~20 lines); absent-flag pinned by test to the 60 s
  default. Headers on the two new files; no drive-bys; negative deltas
  are codec-legal but workload-unused (checker docstring pins the
  positive-delta assumption — fine).
- **Workflow**: the two itemized hunks only — `counter-crash` in the
  default sweep + the `counter-*` token translation; bare tokens
  byte-identical to before.
- **Zero-retry arithmetic for bare `counter-crash`**: verified — at
  the global defaults (rate 10, 300/key) the op phase is ~25 s and can
  end before the first kill window (crash calm 15 s + fault), making
  legal zero-retry runs possible; at the counter defaults (1.4,
  800/key) the op phase is ~285 s of a 300 s run and overlaps every
  window. My crash gates' op phases ran the full wall clock. The
  sustained-defaults deviation is justified.

### Probe (beyond emphasis 4) — leader kill during a quorum-pause window

Ran the Q14 schedule (500 ms window, 5 s delay, 180 s) and killed the
sitting leader (n1) with a raw in-container `kill -9` **two seconds
into the second stall window** — followers frozen, leader dead, a
momentary total outage; nothing in this schedule ever restarts n1, so
the run finished on the surviving four voters. Outcome
(`…counter-quorum-pause/155742.716Z`): the run stays fully coherent —
**liveness `:valid? true`** (the stall windows gate correctly even
with the compound fault inside one, and the 4-voter remainder makes
progress), the conviction is exclusively the legitimate
`:double-count` kind (the active Q14 boundary, as designed — 7 `:info`
ops, retry evidence 201/83, `:exceptions` valid, analysis complete).
No false liveness flags, no spurious violation kinds, no checker
crash. A same-length killer-less control run of the same schedule
(`…155356.235Z`) was equally red with zero `:info` — a third
replication of the Q14 conviction as a bonus.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking (precision; feeds the L3 design record) | `jobs/09-counter-workload/09_report.md` ("The Q14 run"), `harness/src/ratis_jepsen/nemesis.clj:180` (quorum-pause-cycle docstring) | The stalled leader does not "keep appending the whole window's adds": it steps down mid-stall (`StepDownReason:LOST_MAJORITY_HEARTBEATS`, observed every cycle), so the reply-loss population is the pre-step-down slice (~2–4 s) and post-step-down adds are refused NLE. Conviction/calibration unaffected; the mechanism sentence should be amended when the Q14 result moves to the L3 record, and the step-down latency itself is useful calibration data (it bounds the hazard window). |
| 2 | non-blocking | `docs/RUNS.md` (Q14 entry), review-brief posterity | Boundary (b)'s green is genuine but evidentially weaker than (a)'s: its 367 `:info` adds give the bounds ~200+ delta units of 0-or-1 allowance per key, so "no conviction" there does not mean "provably clean" the way (a)'s exact high-water-equals-okSum does. The two-sided calibration should be quoted as (a)-vs-Q14; and the boundary should always be stated as window vs *total attempt span* (rpc timeout + sleep per attempt), which the SUT flag's wording already gets right. |
| 3 | non-blocking | `harness/src/ratis_jepsen/client.clj:invoke-deadline-ms` | The worker report's "25 s invocation deadline" for the Q14 run understates the derived value: 1000 + 4×(3000+5000) = 33 s. Immaterial (both cover the span); the number in the report text is wrong, the code is right. |
| 4 | non-blocking | `jobs/09-counter-workload/09_report.md` (criterion 1) | The report quotes "93 tests / 891 assertions"; the shipped branch runs 96/898 (the PR body has it right). Stale number from before the final test commit. |
| 5 | non-blocking (observation) | `harness/src/ratis_jepsen/checker.clj:retry-totals` | Q14-schedule stores contain a small `:retries 5` tail (10 ops): the harness-deadline cancellation path surfaces one extra policy consultation beyond `RetryLimited`'s nominal 4. Harmless for the evidence law (counts consultations, as documented); worth knowing when reading `:retries` as "attempts". |

## Suggestions (non-blocking)

- When BACKLOG/L3 absorb the Q14 result, fold in finding 1's corrected
  mechanism and quote boundary (a)'s *exactly-clean* forensic (per-key
  high-water == okSum) as the negative control — it is the strongest
  single piece of evidence in the whole calibration.
- The worker's own suggestions are good; exit-code hardening for
  jepsen's `:unknown` analyses (their Known gaps) deserves a job soon —
  a checker crash reading as CI-green is the kind of hole Review 01
  taught this project to close.
- A tiny quorum-pause cycle knob (`--quorum-pause-fault-s`) would let a
  future run hold the stall *below* the step-down latency, isolating
  the pre-step-down slice cleanly — useful if upstream engagement wants
  a minimal repro without step-down noise.

## Verification notes

- Worker branch consumed read-only via `git worktree add
  ../job-09-under-review 0fb8802`; nothing pushed to it.
- Same uncommitted environment accommodations as Review 08 (proxy CA
  shim, host-network build args, volume-seeded Maven repo, git-enabled
  derivative image for the cognitect test-runner git dep. The session's
  proxy changed ports mid-review and the docker daemon restarted once —
  cost setup time, no bearing on any verdict).
- My adversarial fixture script and high-water forensic script live in
  the session scratchpad, not the repo; both operate read-only on the
  worktree/stores.
