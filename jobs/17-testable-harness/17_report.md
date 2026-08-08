# Job 17 report — make the harness's own tests runnable and CI-guarded

## Summary

The harness test suite — the ~2,400 lines covering the outcome map and
the checkers — now runs inside the shipped `control` container
(`env/run.sh selftest`, new), on a bare host, and in CI on every
workflow dispatch (`harness-tests` job, new), and demonstrably fails
the workflow when a test fails. The git-dependency that made
`clojure -M:test` unresolvable in the gitless container is gone,
replaced by a ~70-line in-repo runner on plain `clojure.test` with
directory-based namespace discovery (tools.namespace, an ordinary
Maven dep). The optional capstone §3.4 cleanup was taken too: the
image now ships real `git` and `gnuplot-nox`, so jepsen's provenance
logging uses real git (the `run.sh` shim remains as a guarded no-op
fallback) and stores gain latency/rate plots — both verified live. The
two decisions a reviewer should look hardest at: writing our own
runner instead of adopting a third-party Maven-published one
(cognitect's runner is git-distributed only; the Clojars alternatives
are unmaintained forks — rationale below), and running the CI job
natively on the runner rather than inside the Docker topology (the
integration test needs no cluster; native keeps it at ~30–40 s).

## What was built

- `harness/test/ratis_jepsen/test_runner.clj` — **new**: the
  `-M:test` entry point. Discovers every namespace under `test/`
  (never a hardcoded list, so a new test file cannot be silently
  skipped), excludes itself, runs them with `clojure.test/run-tests`,
  prints the merged summary, and exits 0 iff `fail`+`error` = 0. An
  **empty discovery result exits 1** — an empty suite is a wiring bug,
  not a pass. Explicit `System/exit` because the integration test's
  in-process ratis-kv servers leave non-daemon threads.
- `harness/deps.edn` — `:test` alias: the
  `io.github.cognitect-labs/test-runner` **git** dep (and its
  `:exec-fn`) removed; `org.clojure/tools.namespace {:mvn/version
  "1.5.1"}` added; `:main-opts` now `-m ratis-jepsen.test-runner`.
  Comment explains why (classpath must resolve from Maven alone).
- `env/run.sh` — **new `selftest` subcommand**: installs the SUT jar
  into control's maven-repo volume (`-q -DskipTests` — the SUT's own
  suite runs in `mvnw package` builds and CI's build-sut) and runs
  `clojure -M:test` in `/ratis-jepsen/harness`. Touches no db node;
  exit code is the suite's. Usage header updated.
- `env/Dockerfile` — runtime apt set gains `git` and `gnuplot-nox`,
  with comments stating why and that the run.sh shim stays as a
  guarded fallback for older images.
- `env/README.md` — usage block gains the `selftest` line.
- `harness/README.md` — "Running the tests" rewritten: the Docker-only
  invocation (`env/run.sh up` + `env/run.sh selftest`) is primary; the
  native-host path now states its real prerequisites (JDK 21 + Clojure
  CLI) and that the runner resolves `test/` relative to `harness/`.
- `.github/workflows/jepsen.yml` — **new `harness-tests` job**, no
  `needs:` (runs on every dispatch, parallel to the scenario fan-out):
  checkout → setup-java (temurin 21, maven cache keyed on
  `sut/ratis-kv/pom.xml` + `harness/deps.edn`) → Clojure CLI installed
  at the image's exact pin (1.12.1.1550) → `mvnw -q -DskipTests
  install` → `cd harness && clojure -M:test`. `timeout-minutes: 20`.
  A test failure fails the step and therefore the workflow. That is
  the entire workflow diff — no existing job was touched.
- `jobs/17-testable-harness/17_report.md` — this report.

**Why an in-repo runner and not a Maven-published one**: cognitect's
test-runner is deliberately git-only (no Maven artifact exists — I
checked Clojars for both historical coordinates), and the
`clojure.test`-compatible runners that *are* on Clojars are forks of
circleci.test or one-off uploads — not dependencies to hand a donee.
Plain `clojure.test` + contrib `tools.namespace` (Maven Central,
`org.clojure` group) keeps the dependency surface effectively zero and
the runner small enough to read in one screen.

## How it was verified

**Acceptance 1 — in-container, from a clean `env/run.sh up`, all
green.** On this host (TLS-inspected network, so `up` used the
documented `RJ_EXTRA_CA_BUNDLE` knob; nothing else host-side beyond
Docker):

```
$ env/run.sh up          # image rebuilt with git + gnuplot-nox
run.sh: all 7 nodes ssh-ready
$ env/run.sh selftest
test-runner: discovered 7 test namespaces: [ratis-jepsen.checker-test
  ratis-jepsen.client-test ratis-jepsen.core-test ratis-jepsen.db-test
  ratis-jepsen.integration-test ratis-jepsen.nemesis-test
  ratis-jepsen.outcome-test]
...
Ran 115 tests containing 995 assertions.
0 failures, 0 errors.
test-runner: summary {:test 115, :pass 995, :fail 0, :error 0, :type :summary}
```

Exit code 0 (the invoking shell recorded it). No test failure needed
fixing: the suite had not rotted while unrunnable — deliverable 4's
answer is "all 115 pass, nothing environmental to patch, no defects
found."

**Acceptance 2 — the same suite in CI, green and deliberately red.**
Two real dispatches of the `jepsen` workflow on this branch
(`scenarios=none, time-limit=120`):

- **Green**, at the working commit `7c5415a` — run
  [31216798942](https://github.com/hooji/ratis-jepsen/actions/runs/31216798942),
  `harness-tests`
  [success](https://github.com/hooji/ratis-jepsen/actions/runs/31216798942/job/92992114253),
  job log tail:

  ```
  Ran 115 tests containing 995 assertions.
  0 failures, 0 errors.
  test-runner: summary {:test 115, :pass 995, :fail 0, :error 0, :type :summary}
  ```

- **Red on a deliberate break**, at temporary commit `5d25b87`
  (`row-reply-success`'s write-OK expectation flipped to `:fail`) —
  run
  [31216825312](https://github.com/hooji/ratis-jepsen/actions/runs/31216825312),
  `harness-tests`
  [**failure**](https://github.com/hooji/ratis-jepsen/actions/runs/31216825312/job/92993113158)
  while build-sut / test (none) / red-gate all stayed green (so the
  red is the suite's, not infrastructure's). Job log:

  ```
  FAIL in (row-reply-success) (outcome_test.clj:72)
  write OK ⇒ :ok
  expected: (= {:type :fail} (outcome/classify :write "OK"))
    actual: (not (= {:type :fail} {:type :ok}))

  Ran 115 tests containing 995 assertions.
  1 failures, 0 errors.
  test-runner: summary {:test 115, :pass 994, :fail 1, :error 0, :type :summary}
  ##[error]Process completed with exit code 1.
  ```

  The break was reverted immediately (`22718c0`, a `git revert` of
  `5d25b87`); the branch's final state is the working code, and both
  commits are in the branch history for the reviewer.

**Job runtime**: 39 s (green run) and 30 s (break run) wall on
`ubuntu-latest`, including checkout, JDK, Clojure CLI install, SUT jar
install, and the full suite — GitHub's runners resolve the ~95 MB
dependency set at wire speed, and setup-java's cache makes later
dispatches similar or faster. At this cost the job would be reasonable
on `push` as well; **proposed separately here, not added** (the brief
authorized no new trigger): add `push: {branches: [main]}` scoped to
the `harness-tests` job via a second, trivial workflow if the
coordinator wants post-merge protection between dispatches.

**Acceptance 3 — no regression to existing behavior.**

```
$ env/run.sh test --nemesis partition --time-limit 60
...
Everything looks good! ヽ('ー`)ノ
EXIT=0
```

on the rebuilt image (same run also exercised jepsen's git path — see
below). `env/run.sh down` remains clean (containers, network, volumes
removed).

**Real git / shim interaction (deliverable 5, taken)**: in the new
image `command -v git` → `/usr/bin/git` (2.43.0). `run.sh test`'s shim
install is guarded by `command -v git ||`, so after the full partition
run **`/usr/local/bin/git` does not exist** — verified by `ls` in the
live container post-run — and jepsen's provenance logging ran against
real git without incident. The shim code stays in `run.sh` as a no-op
fallback for images that predate this change, exactly as its comment
always promised. gnuplot: the partition run's store now contains
`latency-raw.png`, `latency-quantiles.png`, `rate.png` — the first
plots ever produced inside the env image (previously local-only, per
the results READMEs' anomaly notes).

**Acceptance 4 — documented prerequisites match reality.** From the
corrected `harness/README.md`:

> **In the shipped environment (needs only Docker)** — from the repo
> root, with the topology up:
> ```
> env/run.sh up          # once
> env/run.sh selftest    # SUT jar install + all unit and integration tests
> ```
> …
> **Directly on a host** that has JDK 21 and the Clojure CLI installed
> (neither is needed for the Docker path above): …

Both forms were executed this job (the Docker form above; the CI job
is the on-host form, minus git — which is no longer needed anywhere).

**Acceptance 5 — ownership and diff discipline.** Files touched:
`harness/test/ratis_jepsen/test_runner.clj` (new), `harness/deps.edn`,
`harness/README.md`, `env/run.sh`, `env/Dockerfile`, `env/README.md`,
`.github/workflows/jepsen.yml`, this report — all inside the declared
ownership; `README.md`/`LICENSE`/`NOTICE`/`docs/**` untouched (Job 16's,
merged while this job ran). The workflow diff is the single
`harness-tests` job block quoted in "What was built".

## Deviations from the brief

- Deliverable 1 named "a Maven-coordinate dependency (or an equivalent
  runner available from Maven)" as the preferred route. No trustworthy
  Maven-published runner exists (cognitect's is git-only by policy;
  the Clojars matches are forks), so the implementation is a minimal
  in-repo runner whose only dependency (`tools.namespace`) *is* a
  Maven coordinate. Same effect — the classpath resolves with no git —
  with less third-party surface; flagged here in case the coordinator
  prefers an external runner anyway.
- None otherwise. The optional deliverable 5 was taken because it cost
  two apt packages and zero changes to `run.sh` logic (the shim guards
  already anticipated real git).

## Known gaps and risks

- The CI job installs the Clojure CLI from
  `download.clojure.org` on every run (pinned version, ~10 s). If that
  endpoint is ever unavailable the job fails visibly; caching the
  installer would remove the dependency but adds cache plumbing for
  marginal gain.
- `selftest` skips the SUT's own Maven suite (`-DskipTests`) to avoid
  duplicating what `mvnw package` and CI build-sut already run; someone
  running *only* `selftest` forever would never execute the SUT unit
  tests. The division of labor is stated in the run.sh comment.
- The runner discovers namespaces from the `test/` directory relative
  to the working directory — correct for `clojure -M:test` from
  `harness/` (the only documented invocation) and guarded by the
  empty-discovery-exits-1 rule if invoked from anywhere else.
- Older already-built images (without git) still get the shim via
  `run.sh test`'s guard — intended — but `selftest` against such an
  image would fail classpath resolution only if the maven-repo volume
  has never cached tools.namespace; a `run.sh up` rebuild is the
  documented path to the current image.
- Job 16 (merged mid-job) reworded the root README's durability-token
  cost sentence; the workflow's own lazyfs comment (mine) still says
  "~2–3 min, every job pays it", which the capstone measured high.
  I left it: this job's workflow diff is deliberately the new job
  block and nothing else; flagging the stale comment for a docs pass
  or the reviewer's discretion.

## Suggestions (out of scope)

- The `push`-trigger proposal above (runtime evidence: 30–39 s/run).
- capstone §3.3 (centralizing the version-pinned log-grep patterns
  with a nothing-matched canary) remains the next-highest-value
  hardening item now that the suite guards the checkers themselves.
