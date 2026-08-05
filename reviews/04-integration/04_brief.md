# Review 04 — integration / M0 exit gate (worker PR: `Job 04: integration: register workload, partition nemesis, M0 exit gate`)

*Coordinator brief, 2026-08-04.*

**Read `reviews/README.md` first — it is binding.** The standard is
`jobs/04-integration/04_brief.md` plus `docs/DESIGN.md` §0/§2.5/§5.
This review closes milestone M0: a MERGE verdict here is a statement
that the harness catches real violations and stays quiet on healthy
clusters. Requires Docker; if your environment lacks it, stop and say
so.

## Baseline (always)

Reproduce the acceptance criteria yourself in the worktree; ownership
check is sharp here: the `env/run.sh` diff must be confined to the
`test` subcommand replacement (diff it hunk-by-hunk), nothing under
`sut/**` or the other `env/` files, `store/`/`target/` absent from the
diff, headers, report completeness.

## Emphasis

1. **Re-run both exit-gate runs yourself — this is the review.**
   (a) `env/run.sh up` then the reference GREEN command from
   `docs/RUNS.md` — expect exit 0, `:valid? true`; (b) the reference
   RED (`--seed-bug stale-reads`) — expect non-zero exit,
   `:valid? false`, and **read the analysis yourself**: confirm the
   violating operation is a genuine stale read (a `:r` observing a
   value that linearizability rules out at that point), not an
   artifact of op timeouts or mis-mapped outcomes. Quote it. Your
   runs' verdicts — not the worker's — are the evidence of record;
   compare against the `docs/RUNS.md` entries and flag any mismatch.
2. **Green stability.** Run the GREEN configuration **twice** (fresh
   `up` between them). M0's value collapses if the green gate is
   flaky. If a green run reports a violation with no seed bug: treat
   exactly as the job brief instructs the worker — that is a
   discovery, preserve the store, verdict REVISE is *not* automatic
   (it may be a real SUT/Ratis finding or a harness bug — determine
   which before writing your verdict, and say so loudly either way).
3. **Outcome-mapping sanity, recomputed.** From *your* green run's
   history, compute `:info` counts inside vs outside nemesis windows
   (state your window definition). A calm-phase `:info` flood is a
   REVISE-grade mapping/timeout defect.
4. **Seed-bug plumbing.** Verify the flag reaches all five nodes'
   start commands (worktree logs / process args), is absent by
   default, and that `db.clj`'s plumbing didn't alter the un-seeded
   start path.
5. **`db.clj` integration fixes.** The worker's report must list each
   first-contact fix; audit each against the DESIGN §2.6 contract
   (paths/ports/user unchanged?) and confirm `clojure -M:test` (unit +
   in-JVM integration from Job 03) still passes after the changes —
   Job 03's review verified that suite; it must not have regressed.
6. **Generator caps and checker cost.** Find the per-key/total op caps
   in the generator code (quote them); report your runs' knossos
   analysis wall-clock; confirm the `--nemesis none` 60 s run is green
   and quiet.
7. **Exit-code propagation.** `run.sh test` must propagate: your red
   run's shell exit code non-zero, green zero — show `echo $?`.

## Probe suggestions (pick at least one)

- Interrupt a `run.sh test` mid-run (Ctrl-C/kill) — then `down`, `up`,
  and a green run: no wedged state, no stale containers/pidfiles.
- Vary `--time-limit` down to 120 s — still green, still bounded
  analysis (guards against budget assumptions hiding in defaults).
- Inspect the red run's store timeline: does the seeded 500 ms
  staleness show up as a *cluster* of read anomalies rather than a
  single lucky catch (crude confidence the detector isn't marginal)?

## Out of scope

CI workflows, elle, crash/pause nemeses, membership/snapshot churn,
increment workload, lazyfs, performance beyond the analysis-time bound,
env polish backlog items.

Deliver `reviews/04-integration/04_report.md` per `reviews/README.md`;
verdict PR titled `Review 04: <verdict>`; self-merge if report-only.
