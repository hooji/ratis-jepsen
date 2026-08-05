# Review 05 report — Job 05: nemesis breadth + liveness checking (M1, harness side)

Worker PR: #10 (`claude/nemesis-breadth-brief-3dxct3`, head `1f21ad9`,
base `473e6a3`). Reviewed in a detached worktree at the PR head; every
run and test below is mine. Docker 29.3.1, x86_64, 4 cores / 15 GB RAM
(same sandbox class as Review 04; same uncommitted accommodations —
slim CA bundle via `RJ_EXTRA_CA_BUNDLE`, `--network host` build args,
JVM-truststore import + git install on control).

## Verdict: REVISE

## Justification

Job 05's own deliverables verify almost everywhere: the liveness
checker survived the worker's 12 unit tests **and all 8 of my
adversarial fabricated histories** (grace-straddling stalls, exact
attempt-gap boundaries, overlapping and unhealed faults, paused-minority
traffic); crash ×2 and pause ran green with every `:info` inside fault
windows and leader-biased targeting demonstrably working; the seeded-red
under crash fires on all five keys with the flag riding every restart;
the elle misfit is real and I reproduced it against my own run's
history. But my reproduction of the **mixed** gate produced a
**false-red**: knossos convicted keys 0, 2 and 3 of a healthy,
un-seeded cluster (`:failures [0 2 3]`, exit 1). I triaged it to
root cause: it is a **harness outcome-map unsoundness** (Job 03's
DESIGN §2.4 row, exposed for the first time by Job 05's fault
schedule), not an SUT/Ratis bug — writes graded
`:fail :not-leader-or-not-ready` ("definite, not appended") can in fact
commit when a deposed leader's appended entries survive into the next
term. A correctness harness that can convict a correct cluster is a
blocking defect wherever the row lives, and the run matrix this job
ships (`mixed`, and in principle any partition-bearing schedule) can
reproduce it. The fix is small, worker-executable, and verified sound
against the ratis-client 3.2.2 source (below).

## The discovery: false-red in `mixed`, triaged to root cause

**Observation** (store
`ratis-kv-register-mixed/20260805T061642.433Z`, preserved): my
`--nemesis mixed --time-limit 300` run exited 1 with
`:failures [0 2 3]`, 4 `Inconsistent` blocks, liveness/stats/exceptions
all valid, `:info-count 0` — no seed bug anywhere.

**Raw-history reconstruction** (all three keys, same fingerprint —
key 2 shown):

- Last completed op before the run's first partition:
  `write [2 2]` `:ok` at t=15.15 s. The partition (halves {n4 n3} |
  {n2 n5 n1}) runs t=15.2→30.3 s; **nothing on key 2 succeeds in or
  after it until t=32.9 s** — every write-path op fails
  `:not-leader-or-not-ready` (fast NLE replies), every read fails
  `:read-index`/`:timeout`.
- `write [2 1]` invoked t=32.497 is graded
  `:fail :not-leader-or-not-ready` at t=32.505 — per the outcome map, a
  *definite* not-appended.
- Yet reads at t=32.914/32.917 return **1**, and a CAS at t=32.998
  fails `:precondition, :current 1` — **the SUT's own apply-time reply
  proves the authoritative register became 1** with no successful
  setter of 1 anywhere in the interval. Keys 0 and 3 flip the same way
  in the same instant (0: `2→3` with NLE'd `write [0 3]` at t=32.567;
  3: `3→2` with NLE'd `cas [3 [3 2]]` at t=32.560).
- Node-log timeline: boot leader n4 (term 1) sat in the partition's
  minority half; the heal lands at t≈30.3; **n3 wins term 2 at
  06:17:30.3 ≈ t=32.8** — the exact moment all three keys flip and the
  convicted reads complete.

**Mechanism**: writes arriving at n4 in its last instants of term-1
leadership were *appended* to its log; when n3's term-2 election
deposed n4, Ratis completed those pending requests with
NotLeaderException (the step-down path), the client's NLE funnel turned
them into `RaftRetryFailureException` with null cause, and the outcome
map graded them **definite `:fail`** — but the entries, freshly
replicated post-heal, survived in the successor's log and committed.
Raft permits exactly this: *a NotLeaderException reply to an in-flight
write is ambiguous, not definite*. Knossos then rightly convicted the
impossible history the harness had written for itself.

**Attribution**: the unsound row is Job 03 code
(`outcome.clj`, mirroring DESIGN §2.4: "NotLeaderException → :fail
(definite; not appended)"), dormant through all of M0 because it needs
a partition heal racing an election with appended-but-unacked writes.
Job 05's `mixed` schedule produced that race in 1 of my 3 mixed runs
(the 300 s run; both 120 s probes and the worker's own mixed run were
green — the trap is intermittent by nature). It is not a defect in any
line Job 05 added, and not an SUT bug — but it is a harness
correctness defect that this PR's own deliverable (the matrix) now
exercises, and it undermines the credibility of every future
un-seeded red until fixed. Per the brief's own escalation clause I am
reporting it loudly rather than completing a clean matrix around it.

**Fix, verified sound against ratis-client 3.2.2 source**:
`BlockingImpl.send` captures `callId` **once**
(`final long callId = CallId.getAndIncrement()`, line 94) and every
retry attempt rebuilds the request with that same callId
(`sendRequestWithRetry(() -> client.newRaftClientRequest(server,
callId, …))`), so a bounded library retry policy re-sends the *same*
`(ClientId, callId)` — the server's retry cache deduplicates, a
step-down-committed entry's retry returns the cached success, and no
double-apply is possible. A null reply (the NLE/LNR funnel) is exactly
the case routed to the retry policy. Under `noRetry` it becomes the
null-cause `RaftRetryFailureException` the map mis-grades today; under
a bounded policy it resolves to the true outcome. See Required
revisions.

## What I verified

### Criterion 1 — `clojure -M:test` green, new suites, no regression

```
control$ cd /ratis-jepsen/harness && clojure -M:test
Ran 53 tests containing 622 assertions.
0 failures, 0 errors.        (exit 0)
```

Matches the report (was 31/252 after Job 04). New namespaces present
and named per scenario (`calm-window-stall-is-flagged`,
`stall-during-or-just-after-fault-is-not-flagged`,
`idle-generator-window-is-not-flagged`, …).

### Emphasis 1 — the liveness checker attacked (all survived)

Beyond re-running the worker's 12 tests, I fed the checker core 8
fabricated histories it had never seen (scratch script, loaded against
the worktree source):

```
A1 grace-straddling stall (64s calm) flags           PASS
A2 straddle w/ only 58s calm does not flag           PASS
B1 attempts at exactly 10.0s gaps chain and flag     PASS
B2 attempts at 10.5s gaps do not chain (no flag)     PASS
B3 idle tail starting inside fault does not flag     PASS
C1 overlapping faults merge; post-merge stall flags  PASS
C2 unhealed overlap gates whole tail (no flag)       PASS
D paused-minority window w/ calm traffic: no flag    PASS
```

The two REVISE-grade behaviors the review brief names — flagging a
healthy-but-idle cluster, missing a post-grace stall — are both
affirmatively excluded: a stall beginning *inside* the grace window
flags exactly when its calm-region portion reaches 60 s (A1/A2), and
zero-invocation windows never flag (B3 + worker's own test). Overlap
robustness (C1/C2) matters because the checker must not misread
histories the current serialized-segment generator can't produce but
M2 compound faults could. The `:max-attempt-gap-s` boundary is exact
and inclusive (B1/B2) — with real workers re-invoking every ~5 s, the
"continuous attempts" reading is safe; its documented insensitivity to
sparser-than-10 s attempting is a sensitivity trade-off, not a false
positive risk.

### Criterion 2 — the matrix (my runs; wall / knossos analysis)

| Run | Exit | Wall | Analysis | ok / fail / info | Verdict |
|---|---|---|---|---|---|
| crash #1 | 0 | 1544 s* | **1222 s** | 996 / 444 / 60 | `:valid? true`, liveness true |
| crash #2 | 0 | 346 s | 23.0 s | 970 / 485 / 45 | `:valid? true`, liveness true |
| pause | 0 | 323 s | 1.4 s | 1063 / 430 / 7 | `:valid? true`, liveness true |
| mixed 300 s | **1** | 334 s | 0.4 s | 694 / 806 / 0 | **false-red (see discovery)** |
| red-crash (seeded) | 1 | 317 s | 0.46 s | — | `:failures [0 1 2 3 4]` ✓ |
| mixed 120 s ×2 (probe) | 0, 0 | 135/134 s | <1 s | — | both `:valid? true` |

\* crash #1's wall is dominated by its analysis: 60 `:info` with a
19-`:info` key took knossos **20 min 22 s** on this 4-core box, vs
23 s for crash #2's 45 `:info` (max 14/key). Same code, same budget —
the knossos cliff the worker lists under Known gaps is empirically
much closer and more variance-prone than the reference numbers
(5.4 s/2.1 s on their hardware) suggest. Bounded, but barely.

### Criterion 3 — seeded-red under crash

Exit 1, `:failures [0 1 2 3 4]`, analysis 0.46 s. The
`*** SEEDED BUG ACTIVE ***` banner appears 6–8× per node (2 per boot ×
3–4 boots) — the flag rides the crash nemesis's `db.clj` restart path
exactly as claimed. Liveness rightly stays valid (stale reads still
acknowledge). Violation from key 0's analysis: a read of `0` convicted
against register `3` at t≈3.0 s — the stale-shadow signature.

### Criterion 4 / emphasis 6 — outcome mapping under crash

My recomputation (windows = `:crash` first entry → `:restart` last
entry; adjacency ≤5 s):

| Run | `:info` | inside | adjacent | outside |
|---|---|---|---|---|
| crash #1 | 60 | **60** | 0 | 0 |
| crash #2 | 45 | **45** | 0 | 0 |
| pause | 7 | **7** | 0 | 0 |

Calm phases completely quiet in every run; reads carry zero `:info`
everywhere. (The pause run's 7 — absent in the worker's run — came
from a leader-pause my run caught; all inside SIGSTOP windows, exactly
the "pause windows overlap client timeouts" behavior their Known gaps
predicts.)

### Emphasis 3 — crash nemesis mechanics

- **Leader bias observed working**: correlating each `:crash` op's
  target set (jepsen.log) with the leader timeline (node logs):
  **8/10 cycles killed the then-current leader in both crash runs**
  (p=0.5 forced + random inclusion predicts ≥~65%).
- **Repeated same-node kills stay clean** (the review probe): every
  node was killed 2–4× per run (crash #1: n2 and n3 ×4). n2's log
  shows 4 startup lines in one run (initial + 3 restarts — the 4th
  kill's window was cut by the time limit), persisted log segments
  reloaded each boot, and re-elections landing it back as LEADER at
  terms 4 and 8. `StartupOption.RECOVER` confirmed in SUT source;
  restart goes through `jdb/start!` → the unchanged `start!*`
  (code-audited), `:started` vs `:already-running` results visible in
  the `:restart` op values. No pidfile wedging anywhere.
- The final cycle's kill regularly lands ~t=295 with its restart cut
  by the time limit; the liveness checker gates that unhealed window
  through history end (conservative), matching the ledger's note.

### Emphasis 4 — pause vs. liveness gating

Live side: the pause run's 10 SIGSTOP cycles (19 `:paused`/`:resumed`
nemesis completions; 10th resume cut by the time limit) produced
liveness `:valid? true` with calm regions tracking the windows.
Fabricated side: attack D — a gated pause window with zero `:ok`
inside it amid healthy surrounding traffic — does not flag. Verified
both ways.

### Emphasis 5 / criterion 6 — the elle decision (not adopted): audited and reproduced

- Shipped source check (elle 0.2.7 jar, `elle/rw_register.clj`):
  ns docstring line 3 — *"Writes are assumed to be unique, but this is
  the only constraint"*; `wr-graph` throws
  `IllegalArgumentException "Key … had value … written by more than one
  op"` by explicit design (the "big flashing warning" comment).
- Empirical, against **my** crash #1 history via the report's
  translator: `check THREW: java.lang.IllegalArgumentException — Key 3
  had value 1 written by more than one op` (1440 completed ops).
- Control: a unique-write history with CAS encoded
  `[[:r k old] [:w k new]]` returns `{:valid? true}` under
  `{:consistency-models [:strict-serializable], :linearizable-keys? true}`.

The misfit is real (our `rand-int 5` values duplicate by design, per
DESIGN §2.5's CAS-hit-rate goal), CAS itself is expressible, and the
M2+ workload prerequisite the worker records is correct. Honest
settle-it, not avoided work.

### Criterion 7 — headers, ownership, artifacts

10 files, +1463/−34 — all `harness/**`, `docs/RUNS.md` (append-only
diff verified), and the job report; nothing under `.github/**`,
`env/**`, root README, or `sut/**`. Apache-2.0 headers on all three
new `.clj` files. No `store/`/`target/` in the diff; worktree
`git status` clean after all runs. Worker report contains every
section `jobs/README.md` requires. Partition behavior pinned verbatim
by `nemesis-test/segment-shapes`. `run.sh down` after the matrix:
0 containers, 0 volumes.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | **blocking** (root cause in Job 03 code; exercised by this PR's matrix) | `harness/src/ratis_jepsen/outcome.clj:244-253` (+ table rows at 213-215; DESIGN §2.4) | Write-path `NotLeaderException`/`LeaderNotReadyException`/null-cause `RaftRetryFailureException` graded *definite* `:fail` is unsound: a deposed leader completes appended pending writes with NLE and the entries can commit under its successor. Produced a reproducible-class false-red in my mixed run (full triage above). Every un-seeded red is untrustworthy until fixed. |
| 2 | non-blocking | `harness/src/ratis_jepsen/workload/register.clj` / DESIGN §2.5 budget | knossos analysis variance is extreme on modest hardware: 20 min vs 23 s for same-shape crash runs (60 vs 45 `:info`; max-per-key 19 vs 14). The cliff the worker documents is nearer than their reference times suggest; strengthens the elle-prerequisite/M2 plan. |
| 3 | non-blocking (documented by worker) | `harness/src/ratis_jepsen/checker.clj` | Default cycles never leave a ≥60 s calm stretch, so in fault-bearing runs the liveness checker guards only run tails; a cross-cycle wedge is gated out. Worker's majority-aware-gating suggestion is the right M2 fix; until then `--crash-calm-s 90` is the workaround their report names. |
| 4 | non-blocking (documented by worker) | `harness/src/ratis_jepsen/nemesis.clj:125-139` | Leader census reads logs, not live state — stale mid-election or for a killed ex-leader. Fine for a 0.5-probability bias (measured 8/10 hit rate); must not be reused for anything correctness-relevant. |
| 5 | non-blocking (documented by worker) | `harness/src/ratis_jepsen/nemesis.clj:167-169` | `:restart` is fire-and-forget (no startup-line await — would misread the previous boot's line). Observed benign across ~40 restarts; a persistently boot-failing node would surface only indirectly. Fold into M2 crash-nemesis hardening. |
| 6 | non-blocking | `jobs/05-nemesis-breadth/05_report.md` | Report/ledger accuracy: every number I could recompute matched (test counts, stores, window classifications, banner behavior). The `:info`-window analysis remains uncommitted session tooling (same gap as Job 04; suggestion re-seconded). |

## Required revisions

All within `harness/**` (Job 05 ownership). Item 1 amends a DESIGN
§2.4 row and the Q3 `noRetry` decision — make the change, document it
prominently in your report's Deviations as awaiting coordinator
ratification of the DESIGN text (DESIGN.md is not in your ownership;
the coordinator owns that edit).

1. **`harness/src/ratis_jepsen/client.clj` — bounded same-callId
   retries.** Replace `RetryPolicies/noRetry` with a bounded fixed-sleep
   policy, e.g.
   `(RetryPolicies/retryUpToMaximumCountWithFixedSleep 4 (TimeDuration/valueOf 200 TimeUnit/MILLISECONDS))`
   (~0.8 s worst-case added latency; stays under the 5 s
   `invoke-timeout-ms`). Soundness (cite in the docstring):
   `BlockingImpl.send` captures the callId once and rebuilds the same
   request per attempt, so retries are deduplicated by the server retry
   cache — a step-down-committed write's retry returns the cached
   success instead of a lying NLE; no double-apply is possible.
2. **`harness/src/ratis_jepsen/outcome.clj` — make the residual
   ambiguous.** With retries exhausted (prolonged leaderlessness), a
   null-cause `RaftRetryFailureException` — and raw
   `NotLeaderException`/`LeaderNotReadyException` — may still cover an
   appended-then-deposed attempt: write-path (`:write`/`:cas`) verdict
   becomes **`:info`** (keep `:fail` for reads). Update the ns
   docstring's table + rationale (deposed leaders complete appended
   pending requests with NLE; entries can commit under the successor —
   observed in the preserved mixed store, mechanism verified in
   ratis-client 3.2.2 source).
3. **`harness/test/ratis_jepsen/outcome_test.clj`** — update the
   affected expectations; add explicit write-vs-read cases for the three
   rows in item 2.
4. **Re-run the gates on the revised code**: `mixed` ×3 at 300 s (the
   trap is intermittent — one green is not evidence), `crash` ×2,
   `pause`, `partition`, and the seeded-red under crash (must still
   convict). Report wall + analysis times: expect *fewer* `:info` than
   today (retries resolve most transients definitively) — if any run's
   analysis regresses past the ~5 min mark, say so and shrink
   per the Job 04 lever.
5. **`docs/RUNS.md` + your report**: append the re-run ledger entries;
   record the retry-policy/outcome-row change under Deviations with the
   DESIGN §2.4/Q3 amendment flagged for the coordinator; reference the
   preserved false-red store
   (`ratis-kv-register-mixed/20260805T061642.433Z` in the review
   environment; reproduce locally if you want your own artifact —
   ~1-in-3 mixed runs on 4 cores).

## Suggestions (non-blocking)

- Coordinator: ratify the DESIGN §2.4/Q3 amendment (revision 1–2's
  deviation) and retire the "NLE = definite" line from DESIGN; note
  that M0's partition gate carried the same latent false-red risk and
  its greens (worker's and both reviews') were unaffected by luck.
- Second the worker's majority-aware liveness gating (M2) — after the
  retry change, re-examine `:max-attempt-gap-s` against the new
  (longer) attempt cadence under faults.
- Second the elle-mode workload prerequisite (unique writes,
  read-informed CAS) — Finding 2's analysis-variance data strengthens
  the urgency.
- Commit the `:info`-window/leader-bias analysis tooling (re-seconded
  from Review 04).
- The worker's crash/pause cycle knobs proved handy in review
  (`--crash-calm-s 90` liveness workaround); document them in the
  README once Job 06's README lands.
