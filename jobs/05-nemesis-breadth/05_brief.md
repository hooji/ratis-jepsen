# Job 05 — nemesis breadth + liveness checking (M1, harness side)

*Coordinator brief, 2026-08-04.*

**Before anything else, read `jobs/README.md` — it is binding.** Then
`docs/PLAN.md` (§5 M1), `docs/DESIGN.md` (§2.5, §6), `docs/BACKLOG.md`
item 4 context, and this brief. Base your branch on current `main`
(M0 = jobs 01–04 merged; the register workload runs green under
partition, and the seeded-red gate works).

## Context

M0 proved the pipeline on one nemesis. M1 widens the fault surface to
the classes behind Ratis's real tracker history (crash-restart:
RATIS-2523/2306-class; pause: election churn) and adds the check M0
lacks: **liveness** — a cluster that stops serving while healthy is
today invisible to the linearizability checker. This job is
harness-only and runs in parallel with Job 06 (CI; owns `.github/**`,
`env/**`, root `README.md`) — you must not touch those paths.

## Deliverables

1. **Crash-restart nemesis** (`ratis-jepsen.nemesis`): kill (`kill -9`
   via the db primitives Job 03 built) and restart SUT processes on a
   random minority of nodes — biased to include the current leader some
   of the time — on a configurable cycle (default: fault 10 s,
   heal/restart, calm 20 s). Restart must reuse `db.clj` start (RECOVER
   semantics are the SUT's job).
2. **Pause nemesis**: SIGSTOP/SIGCONT of the SUT process on a random
   minority (default cycle 5 s stopped / 25 s running).
3. **CLI**: `--nemesis none|partition|crash|pause|mixed` — `mixed`
   interleaves the three randomly with roughly equal weight. Keep the
   Job 04 partition behavior unchanged.
4. **Liveness checker** (`ratis-jepsen.checker` or within nemesis ns —
   your call, documented): flags `:valid? false` when a window of
   **T = 60 s with a healthy majority** (no active nemesis fault, plus
   a grace period G = 15 s after each heal) contains **zero `:ok`
   operations** while invocations were attempted. Nemesis-aware gating
   comes from the history's nemesis events, not wall-clock guesswork.
   Compose it into the existing checker stack. Unit tests with
   fabricated histories are mandatory: a calm-window stall is flagged;
   a stall during/just-after a fault is not; an idle-generator window
   (no invocations) is not.
5. **elle decision** (DESIGN M1 said "elle replaces knossos as
   primary"): investigate `elle.rw-register` against our CAS-bearing
   register history. If CAS ops don't fit elle's inference model
   cleanly, **do not force it**: keep knossos-per-key as primary and
   document the misfit + what workload shape elle would need (that
   becomes an M2+ input). Either way the decision, with evidence, goes
   in your report — this is a settle-it-honestly deliverable, not a
   must-adopt.
6. **Runs + ledger** (`docs/RUNS.md`, append): green runs for `crash`
   (×2 — stability matters most here), `pause`, and `mixed` (300 s
   each); plus one seeded-red run under `crash` proving the detector
   still fires amid restarts. Outcome-mapping sanity for the crash run
   (`:info` clusters at kill moments, calm phases quiet).
7. **`jobs/05-nemesis-breadth/05_report.md`** per `jobs/README.md`.

## File ownership

May create/modify: `harness/**`, `docs/RUNS.md` (append),
`jobs/05-nemesis-breadth/05_report.md`. Nothing else — **not**
`.github/**`, `env/**`, root `README.md` (Job 06 owns those, in
parallel), not `sut/**`.

**Parallel-safe with: Job 06.**

## Acceptance criteria (command + output excerpt each)

1. `clojure -M:test` green — including the new liveness-checker unit
   tests (name them per scenario) and no regression in Jobs 03/04
   suites.
2. Green runs: `crash` ×2, `pause`, `mixed` — checker `:valid? true`,
   exit 0, wall-clock and knossos analysis time reported for each.
3. Seeded-red under `crash`: non-zero exit, violation quoted.
4. Outcome-mapping evidence for the crash run (`:info` vs kill
   timestamps).
5. A fabricated-stall demonstration: show the liveness checker turning
   a doctored quiet-cluster history `:valid? false` with its evidence
   output (unit-test output excerpt is fine).
6. elle decision documented with evidence (adopted code or misfit
   analysis).
7. Headers, ownership, report per `jobs/README.md`.

## Non-goals

CI (Job 06), membership/snapshot churn (M2), follower reads (M2),
increment workload (M3), lazyfs (M4), env or SUT changes. If a green
run surfaces a real violation: preserve the store, triage
harness-vs-SUT, report loudly — that outranks completing the matrix.
