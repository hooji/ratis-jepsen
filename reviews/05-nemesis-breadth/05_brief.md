# Review 05 — nemesis breadth + liveness checking (worker PR: `Job 05: …`)

*Coordinator brief, 2026-08-04.* **Read `reviews/README.md` first.**
Standard: `jobs/05-nemesis-breadth/05_brief.md`. Requires Docker.

## Baseline

Reproduce acceptance criteria in the worktree (`clojure -M:test`
incl. Jobs 03/04 suites; the run matrix); ownership (`harness/**`,
`docs/RUNS.md` append, report), headers, no artifacts.

## Emphasis

1. **The liveness checker is the review.** Re-run its unit tests, then
   attack it with at least two fabricated histories the worker didn't
   test — suggested: a stall straddling a heal boundary (starts inside
   the grace window, extends past it); an idle-generator window (no
   invocations — must NOT flag); overlapping faults in `mixed` mode.
   A checker that can flag a healthy-but-idle cluster, or miss a
   post-grace stall, is REVISE-grade — this component gates every
   future run's meaning.
2. **Re-run the matrix yourself**: crash ×2 (stability), pause, mixed
   (green, exit 0), seeded-red under crash (non-zero, violation
   quoted). Report knossos analysis times.
3. **Crash nemesis mechanics**: restart goes through `db.clj` start
   (RECOVER), leader-biased targeting actually happens (evidence from
   history/logs), no pidfile/state wedging across repeated cycles.
4. **Pause vs. liveness gating**: SIGSTOP windows must gate the
   liveness checker exactly like other faults — confirm a paused
   minority during calm generator traffic can't produce a false stall
   flag.
5. **The elle decision**: audit whichever way it went. If adopted:
   verify the history format actually satisfies elle's model
   (CAS handling especially). If deferred: verify the misfit argument
   against elle's docs/source — it must be a real incompatibility,
   not avoided work.
6. **Outcome-mapping under crash**: `:info` clusters at kill
   timestamps; calm phases quiet (recompute from your run).

## Probe (≥1)

Kill the *same* node repeatedly across 3 cycles (does restart stay
clean?); or run `mixed` at `--time-limit 120` twice for flakiness.

Deliver `reviews/05-nemesis-breadth/05_report.md`, verdict PR
`Review 05: <verdict>`, self-merge if report-only.
