# Review 10 — FUSE spike (worker PR #21)

*Coordinator brief, 2026-08-06.* **Read `reviews/README.md` first.**
Standard: `jobs/10-fuse-spike/10_brief.md`. This is a **lightweight
review** — the PR contains a report and a throwaway workflow, no
production code. The deliverable under review is *evidence quality*,
because M4's entire design (scope and runner choice) will be built on
this report's claims.

## Emphasis

1. **Re-run the hosted-runner proof yourself**: dispatch
   `fuse-spike.yml` from the PR branch, confirm it goes green, and
   read its logs end-to-end — the mount via `/dev/fuse`, the write,
   the fault trigger, and the demonstrated loss of un-synced data must
   all be visible in the run you dispatched, not just the worker's.
2. **Reproduce the local demonstration once** from the report's
   command transcript (unmodified `env/` image, ad-hoc container).
   Note any step that didn't work as written.
3. **The load-bearing claim — grade its evidence hard**: the
   single-node rehearsal found that `ratis-kv` recovers *every*
   acknowledged write after a clear-cache fault in both orderings
   (inference: Ratis syncs each append before acknowledging), which
   redirects M4 toward torn-write and quorum-wide fault shapes.
   Reproduce the rehearsal once; check the logs actually support the
   sync-before-ack inference (not merely "no loss observed"); state
   plainly how strong the evidence is, since M4's scope hangs on it.
4. **Practicalities M4 inherits**: confirm the two accommodations
   (dependency pre-fetch behind the proxy; the ~8 s / 1 GiB per-mount
   pre-allocation cost) are accurately characterized — the second one
   multiplies across five nodes and belongs in M4's startup budget.
5. **Hygiene**: ownership is exactly the two files; the workflow is
   dispatch-only and clearly marked as a spike artifact; lazyfs pinned
   by commit; headers.

## Probe (optional, small)

Vary one parameter in the local demonstration (e.g. sync a subset of
writes before the fault and confirm only the un-synced ones vanish) —
sharpens the tool-works evidence for free.

Deliver `reviews/10-fuse-spike/10_report.md`; verdict PR
`Review 10: <verdict>`; self-merge if report-only.
