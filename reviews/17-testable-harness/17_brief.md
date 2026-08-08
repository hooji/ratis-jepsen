# Review 17 — testable harness + CI guard (worker PR #31)

*Coordinator brief, 2026-08-07.* **Read `reviews/README.md` first.**
Standard: `jobs/17-testable-harness/17_brief.md` + the capstone item it
implements (`jobs/15-donation-readiness/15_report.md` §2.4). Requires
Docker.

## What this job is defending against

The capstone found ~2,400 lines of tests for the code that decides
convictions, un-runnable in the shipped environment and absent from
CI. This PR fixes that — but its two central decisions (a **home-grown
~70-line test runner** replacing the git-distributed cognitect one,
and running the CI job **natively rather than in the topology**) both
introduce the same new failure mode as the old one, wearing different
clothes: **tests that appear to run but don't.** Your review should be
organized around proving that cannot happen.

## Emphasis

1. **Test-count parity, before vs after.** Establish independently how
   many tests and assertions existed before this change (earlier
   reviews recorded 107 tests / 958 assertions at the Job 12 era; check
   the current baseline on `main` yourself if you can run it anywhere)
   and confirm the new runner reports the same or more. A namespace
   silently not discovered is the defect this whole job exists to
   prevent — quote both counts.
2. **Attack the runner's discovery and reporting.** It uses
   directory-based namespace discovery. Probe at least: (a) add a
   throwaway test namespace in an unusual-but-valid location and
   confirm it is discovered; (b) make a test **fail** and confirm the
   runner exits non-zero *and* says so legibly; (c) make a test
   namespace **throw at load time** (e.g. a bad require) and confirm
   that surfaces as a failure rather than a silently skipped
   namespace — load-time errors are the classic way a hand-rolled
   runner reports green on nothing. Revert your probes.
3. **The deliberate-break demonstration in CI — reproduce it
   yourself.** The acceptance criteria required showing the workflow
   red when a test fails. Do not take the report's word or its linked
   run: break a test on a scratch branch, dispatch the workflow, and
   confirm the run fails. This is the single most important check in
   this review; a test job that cannot fail is worse than no test job,
   because it manufactures confidence.
4. **Native-vs-container divergence.** The tests must be runnable in
   the shipped container (`env/run.sh selftest`) *and* they run
   natively in CI. Verify both paths execute the same suite (same
   count) and that neither can pass while the other would fail —
   specifically, whether anything about the container path (JDK,
   Clojure version, dependency resolution) could drift from the CI
   path without anyone noticing. If the two can diverge, say what
   would catch it.
5. **The git/gnuplot addition (optional item, taken).** Adding real
   `git` interacts with `run.sh`'s deliberate failing-git shim and with
   jepsen's provenance logging. Verify: a normal scenario run still
   completes and its store is well-formed; whatever the shim now does
   is coherent and documented; and the new plots actually appear.
   Confirm no image-size or build-time surprise worth flagging.
6. **Documented prerequisites now true.** Follow `harness/README.md`'s
   test instructions literally, in the container, from a clean `up` —
   the capstone's original complaint was precisely that the documented
   command didn't work.
7. **Ownership**: `README.md`, `LICENSE`, `NOTICE`, `docs/**` belong to
   Job 16 (merged separately) — confirm this PR didn't touch them.

## Probe (≥1, beyond emphasis 2's three)

Run the full workflow dispatch once unmodified and confirm the test
job's runtime matches the report's claim; or check what happens if the
test job passes but a scenario job fails (the workflow's overall status
must still be red).

## Note

This is likely the final review of the project before an upstream
offer is drafted. If anything about this PR would embarrass us in
front of Ratis maintainers — a runner that skips tests, a CI job that
can't fail — that is exactly what this review exists to catch.

Deliver `reviews/17-testable-harness/17_report.md`; verdict PR
`Review 17: <verdict>`; self-merge if report-only.
