# Job 17 — make the harness's own tests runnable and CI-guarded

*Coordinator brief, 2026-08-07.*

**Read `jobs/README.md` first — binding.** Then
`jobs/15-donation-readiness/15_report.md` §2.4 (your specification, in
full) and §3.3–3.4, then `harness/README.md`, `env/Dockerfile`,
`env/run.sh`, `.github/workflows/jepsen.yml`. Base on current `main`.

## Context

The capstone donation-readiness review found that this repository's
own unit tests — ~2,400 lines covering `outcome.clj` and
`checker.clj`, the code that decides whether a run convicts — **cannot
be run in the environment the project ships, and run in no CI**:

- `harness/README.md` documents `clojure -M:test`, but inside the
  `control` container it fails with `Error building classpath. Unable
  to clone https://github.com/cognitect-labs/test-runner.git` — the
  `:test` alias declares the runner as a **git** dependency, and the
  image ships no git.
- `env/run.sh test` additionally installs a fake `/usr/local/bin/git`
  that always exits 1 (an intentional shim for jepsen's provenance
  logging), so git is not merely absent but actively stubbed.
- Consequently the tests run only on a host with Clojure CLI, git and
  JDK 21 installed natively — prerequisites the front page never
  states (it requires only Docker).
- No workflow invokes the test suite.

The capstone's framing, which I endorse: this is "the worst kind of
trap" for a donee — an excellent test suite they will not discover is
broken-by-environment until they try to change something.

## Deliverables

1. **Make `clojure -M:test` work inside the shipped `control`
   container.** Preferred route per the capstone: replace the git
   dependency on cognitect test-runner with a Maven-coordinate
   dependency (or an equivalent runner available from Maven), so no
   git is required to resolve the classpath. Adding real git to the
   image is an acceptable alternative *only* if you also resolve the
   interaction with `run.sh test`'s deliberate git shim without
   weakening jepsen's behavior — if you take that route, explain the
   interaction in your report. Whichever route: the documented command
   must work from a clean `env/run.sh up`, with no host-side
   prerequisites beyond Docker.
2. **Add a CI job that runs the harness test suite** (`harness-tests`
   or similar) in `.github/workflows/jepsen.yml`: cheap, fast, and run
   on every dispatch of the workflow — it must not be an opt-in the
   next person forgets. It should fail the workflow when a test fails.
   State its runtime in your report; if it is fast enough to justify
   running on push as well, say so and propose that separately rather
   than adding a trigger this brief did not authorize.
3. **Fix the documented prerequisites.** `harness/README.md` (and any
   other doc you own that states how to run the tests) must describe
   the working invocation — including, if it differs, the in-container
   versus on-host forms. Do not touch the root `README.md`: Job 16
   owns it in parallel; if it needs a change, report it and the
   coordinator will relay.
4. **Verify the suite actually passes** once runnable — all of it, in
   the container. A test that has silently rotted while unrunnable is
   exactly what this job exists to surface; if you find failures,
   **fix them only if they are environmental**, and report anything
   that looks like a real defect rather than patching it.
5. **Optional if cheap** (capstone §3.4): ship real `git` and
   `gnuplot` in the image, retiring the shims that "work but read as
   warts" and restoring latency plots for CI runs. Take this only if
   it does not complicate deliverable 1; otherwise report it as
   deferred with your reasoning.

## File ownership

`harness/**`, `env/**`, `.github/workflows/jepsen.yml`,
`jobs/17-testable-harness/17_report.md`. **Not** `README.md`,
`LICENSE`, `NOTICE`, `docs/**` — Job 16 owns those in parallel.
**Parallel-safe with: Job 16.**

## Acceptance criteria (command + output excerpt each)

1. From a clean checkout: `env/run.sh up`, then the documented test
   command **inside the container**, all tests green — with the
   command and the summary line quoted.
2. The same suite runs in CI: link a real workflow run from your
   branch showing the test job green, and show it failing when a test
   is deliberately broken (revert the break; include both outputs).
3. No regression to existing behavior: one scenario run
   (`--nemesis partition`, short) still completes normally, and
   `run.sh test`'s jepsen invocation is unaffected by whatever you did
   to git.
4. Documented prerequisites match reality (quote the corrected text).
5. Ownership respected; workflow diff itemized; report per
   `jobs/README.md`.

## Non-goals

New tests, new scenarios, the elle migration, log-pattern
centralization (capstone §3.3 — a separate future job), root README
changes, anything upstream.

## Note

A reviewer will assess this one (it is code and CI, not prose). Expect
the review to check specifically that the test job cannot silently
pass when tests fail — the deliberate-break demonstration in
acceptance criterion 2 is the evidence for that, so do it properly.
