# Review 05 (round 2) report — Job 05 Revision 1: retry-policy/outcome amendment

Worker PR: #10, revision commits `722f316..980912d` (head `980912d`).
Round 1 (`05_report.md`) returned REVISE on one blocking finding: the
outcome map's "NotLeaderException ⇒ definite write `:fail`" produced a
reproducible false-red on a healthy cluster. The coordinator ratified
the prescribed amendment into DESIGN §2.3/§2.4 and PLAN Q3 before the
revision landed (`05_revision_1.md`). This round reviews the delta and
spot-checks the previously verified surface. Reviewed in a fresh
worktree at the revision head; all runs mine; same sandbox
accommodations as round 1.

## Verdict: MERGE

## Justification

Required revisions 1–5 were executed exactly as written, and the fix
holds under my independent re-runs: the `mixed` gate — 1-in-3
false-red before the fix — is now **3-for-3 green**, a dedicated
partition run (10 more heal elections, the defect's exact racing
shape) is green, crash and pause are green, the seeded-red still
convicts all five keys, and across **all seven** run histories there
is not a single write-path `:fail` graded with a leadership error —
the unsound row is extinct in practice, not just in the table.
Retries make histories cleaner, not costlier: `:info` collapsed from
60 (round 1's four greens) to 27 (six greens), every remaining `:info`
sits inside a fault window, and knossos analysis stayed at 0.3–0.5 s
in every run — round 1's 20-minute outlier shape has no fuel left.
The implementation matches the ratified DESIGN/PLAN text verbatim.
The previously verified surface (liveness checker, unit suite) held
its spot-checks. No blocking findings remain.

## What I verified (the delta — revisions 1–5)

**Revision 1 — `client.clj` bounded same-callId retries.**
`RetryPolicies/retryUpToMaximumCountWithFixedSleep(4, 200 ms)` replaces
`noRetry` — the exact policy/parameters prescribed (~0.8 s worst-case
fast-fail cost under the 5 s harness deadline); the docstring carries
the soundness argument with the prescribed citation. Matches DESIGN
§2.3 as ratified.

**Revision 2 — `outcome.clj` residual ambiguity.** Write-path
`NotLeaderException`, `LeaderNotReadyException`, and null-cause
`RaftRetryFailureException` → `:info`; reads stay `:fail`; the
non-null-cause row keeps `:info` and drops its loud log (its
"cannot happen under noRetry" premise is gone — a reasonable
tightening I checked against the new mechanics);
`ResourceUnavailableException` correctly remains a definite `:fail`
(admission control is genuinely pre-append). Table and rationale
rewritten citing the round-1 triage.

**Revision 3 — `outcome_test.clj`.** Explicit write-vs-read cases for
all three amended rows, plus the quiet-ambiguity + preserved-cause
assertion for exhausted retries.

```
control$ clojure -M:test
Ran 53 tests containing 624 assertions.
0 failures, 0 errors.        (exit 0)
```

**Revision 4 — gate re-runs, mine** (fresh `up`; 300 s each; wall /
knossos analysis from jepsen.log):

| Run | Exit | Wall | Analysis | ok / fail / info | Verdict |
|---|---|---|---|---|---|
| mixed #1 | 0 | 322 s | 0.32 s | 1075 / 425 / 0 | `:valid? true`, liveness true |
| mixed #2 | 0 | 316 s | 0.33 s | 1067 / 433 / 0 | `:valid? true`, liveness true |
| mixed #3 | 0 | 316 s | 0.33 s | 1081 / 419 / 0 | `:valid? true`, liveness true |
| crash | 0 | 318 s | 0.52 s | 1105 / 382 / 13 | `:valid? true`, liveness true |
| pause | 0 | 316 s | 0.43 s | 1083 / 417 / 0 | `:valid? true`, liveness true |
| partition | 0 | 317 s | 0.47 s | 1099 / 387 / 14 | `:valid? true`, liveness true |
| crash + seed-bug | **1** | 315 s | 0.41 s | 1078 / 410 / 12 | `:valid? false`, `:failures [0 1 2 3 4]` |

The decisive checks on those histories:

- **The defect's grading is extinct**: `grep` for write-path `:fail`
  with `:not-leader` / `:leader-not-ready` /
  `:not-leader-or-not-ready` across all seven `history.edn` files —
  **zero hits**. Leadership transients now either resolve to their
  true outcome via the same-callId retry (visible as the uniformly
  *higher* `:ok` counts vs round 1) or exhaust into windowed `:info`.
- **The racing shape is exercised**: my mixed draws contained 3+2+1
  partition segments plus the dedicated partition run's 10 — ≥16
  partition-heal elections under write load across the re-runs, the
  exact sequence that produced round 1's false conviction. All green.
- **Mapping still calm-phase silent**: crash 13/13 and partition
  14/14 `:info` inside fault windows (my recomputation; zero adjacent,
  zero outside); the three mixed runs and pause had zero `:info` at
  all.
- **No knossos regression** — the opposite: 0.3–0.5 s everywhere.
  Cleaner histories (fewer crashed ops) starve the state-space blowup
  that produced round 1's 20-minute outlier.
- **Seeded-red unimpaired**: retries cannot launder a stale read —
  still convicted on all five keys (`can't read 2 from register 4`,
  key 4), seed banner ×4 in restarted nodes' logs (2 per boot — the
  flag still rides the crash-restart path).

**Revision 5 — ledger + report.** `docs/RUNS.md` gained the
revision-1 re-run table (worker's own eight runs — numbers of the
same shape as mine: all-green + red, `:info` 44 vs their 60, analysis
sub-second); the job report gained the "Revision 1" section with the
defect summary, the code-change map, deviations history, and the
ratified-before-landing note, exactly per the relay.

## Spot-checks of previously verified surface

- **Liveness checker untouched by the revision — behaviorally
  confirmed**: my 8 adversarial fabricated histories from round 1
  (grace-straddle, gap boundaries, overlaps, paused-minority) — all
  still PASS on the revision head.
- Suite count moved 622 → 624 assertions with the reworked rows; no
  other test regressed.
- Ownership: revision touches `harness/src/ratis_jepsen/client.clj`,
  `outcome.clj`, `outcome_test.clj`, `docs/RUNS.md` (append), and the
  job report — inside Job 05's ownership. `docs/DESIGN.md`/`PLAN.md`
  were amended by the coordinator's own commit, not the worker's.
  Headers intact; no artifacts in the diff; `run.sh down` after the
  matrix left 0 containers.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking (observation) | `harness/src/ratis_jepsen/client.clj:108-117` | The 4 × 200 ms budget resolves fast leadership rejections but a *slow* attempt (rpc timeout ≈ 3 s) can eat the whole 5 s harness deadline before exhaustion — those surface as `:harness-timeout`/`:timeout` `:info`, which is correct and windowed (observed: all 27 green-run `:info` inside fault windows). Nothing to change; noting the interplay for M2 tuning. |
| 2 | non-blocking (carried from round 1) | — | Round 1's non-blocking findings (tail-only liveness gating under default cycles, log-based census staleness, fire-and-forget restart, uncommitted analysis tooling) are unchanged by design and remain tracked for M2; the knossos-variance finding is materially *improved* by this revision (cleaner histories) though the elle-prerequisite plan stays the right endgame. |

## Suggestions (non-blocking)

- Coordinator: when merging, the squash message should mention the
  DESIGN §2.3/§2.4 + PLAN Q3 amendment so the harness history and the
  design history cross-reference cleanly.
- Job 06's suggested green-side red-gate symmetry (assert
  `:valid? true` evidence in green CI legs) is now safe to adopt —
  with this fix merged, a green run's verdict is trustworthy.
