# Review 17 report — testable harness + CI guard

Worker PR #31, head `claude/donation-readiness-brief-02typ1` @
`f67dfe3`, reviewed against `jobs/17-testable-harness/17_brief.md` and
capstone §2.4 (+§3.4), organized — per the review brief — around
proving that "tests that appear to run but don't" cannot happen.
Worker branch consumed read-only (`git worktree add
../job-17-under-review FETCH_HEAD`); fresh topology built from the
worktree (Docker required and used).

## Verdict: MERGE

## Justification

Every acceptance criterion held under my own hands: the documented
Docker-only invocation works from a clean `up` (41 s on a cold volume,
`Ran 115 tests containing 995 assertions. 0 failures`); test-count
parity with the pre-change suite is proven two independent ways — my
own Review 12 measurement with the *old* cognitect runner (115/995 on
what is byte-for-byte today's test tree), and a same-container A/B in
which I swapped the old `:test` alias back in and got the identical
115/995; the runner survived four adversarial probes (unusual-location
discovery, failing test, load-time error, wrong-CWD) with a loud
non-zero exit each time; and — the review's centerpiece — **my own
deliberate break on my own scratch branch, dispatched by me, turned
the workflow red** with exactly my planted assertion in the job log
while every other job stayed green, and my unmodified dispatch ran
green at 29 s (inside the claimed 30–39 s). The git/gnuplot addition
behaves as claimed: a partition run on the new image completes green
with the first in-env latency/rate plots, and the always-fails git
shim provably never installs (real git 2.43.0 used). One narrow
discovery hole exists — a test file whose *first form* is
reader-broken is silently skipped — but I measured the old cognitect
runner doing exactly the same on the same probe, so it is a
pre-existing limitation of parse-based discovery, not a regression
this PR introduces; it is a non-blocking finding with a cheap fix.
Ownership is clean. Zero blocking findings.

## What I verified

### Emphasis 1 — test-count parity, before vs after

The review brief's "107 tests / 958 assertions at the Job 12 era"
does not match my records; superseding it with direct measurements:

- **Before (old runner, old tree)**: Review 12 (my own run, cognitect
  runner via git dep, Job 12 head): `Ran 115 tests containing 995
  assertions. 0 failures, 0 errors.` `git diff 543d031 3915d1a --
  harness/` shows only `README.md` changed since — today's test tree
  is byte-identical to the one I measured.
- **Before (old runner, same tree, same container — A/B)**: I checked
  the pre-change `deps.edn` into the worktree and ran the *cognitect*
  runner inside the new container (real git makes its git dep
  resolvable): `Ran 115 tests containing 995 assertions. 0 failures.`
- **After (new runner)**: container (`selftest`) and CI-native (my
  dispatch) both:

  ```
  test-runner: discovered 7 test namespaces: [ratis-jepsen.checker-test
    ratis-jepsen.client-test ratis-jepsen.core-test ratis-jepsen.db-test
    ratis-jepsen.integration-test ratis-jepsen.nemesis-test
    ratis-jepsen.outcome-test]
  Ran 115 tests containing 995 assertions.
  0 failures, 0 errors.
  test-runner: summary {:test 115, :pass 995, :fail 0, :error 0, :type :summary}
  ```

**115/995 == 115/995, all four ways.** No namespace lost.

### Emphasis 2 — attacking the runner (probes all reverted)

All probes ran in-container against the bind-mounted worktree;
`git status` clean afterwards and a final clean run re-confirmed
7 namespaces / 115 / 995 / exit 0.

- **(a) Unusual-but-valid location** — `test/toplevel_probe_test.clj`,
  namespace `toplevel-probe-test` (top of `test/`, no package
  directory, no `ratis-jepsen` prefix): **discovered and run** —
  `discovered 8 test namespaces: […, toplevel-probe-test]`,
  `Ran 116 tests containing 996 assertions`, exit 0.
- **(b) Failing test**: flipped the probe's assertion — exit **1**,
  and legible:

  ```
  FAIL in (review-probe-discovered) (toplevel_probe_test.clj:7)
  expected: (= 5 (+ 2 2))
    actual: (not (= 5 4))
  …
  test-runner: summary {:test 116, :pass 995, :fail 1, :error 0, :type :summary}
  ```

- **(c) Load-time throw** (require of a nonexistent namespace): the
  run aborts before testing anything — zero `Ran N tests` lines — with
  the cause named, exit **1**:

  ```
  Execution error (FileNotFoundException) at toplevel-probe-test/…loading
    (toplevel_probe_test.clj:3).
  Could not locate ratis_jepsen/namespace_that_does_not_exist… on classpath.
  ```

  Not a silent skip: the classic hand-rolled-runner failure mode does
  not occur here, because discovery is by ns-decl parse but *loading*
  is an unguarded `require` — any load error kills the run loudly.
- **(d) My extra probe — reader-broken first form** (file whose `(ns …`
  form is truncated/unreadable): **silently skipped** — discovery
  stays at 7, suite green 115/995, exit 0, no mention of the file.
  Then the control: the **old cognitect runner, same broken file, same
  container — identical behavior** (green, exit 0). Both runners
  discover via `tools.namespace`'s ns-decl parse, which returns nil
  for an unreadable first form. Pre-existing limitation, not a
  regression; finding 1 below with a ~5-line hardening.
- **(Bonus) Wrong CWD**: from a directory with the same deps but no
  `test/`, the invocation exits 1 loudly — the runner itself lives
  under `test/` (a relative `:extra-paths`), so it is not even on the
  classpath (`Could not locate ratis_jepsen/test_runner…`); the
  in-runner empty-discovery guard (`FAIL — no test namespaces found…
  exit 1`) backstops the subtler empty-`test/`-exists case.

### Emphasis 3 — the deliberate break, reproduced by me

Not taking the report's word (though I did verify its two linked runs
exist with the claimed shas and conclusions — run 8 `7c5415a` success,
run 9 `5d25b87` failure): I made my **own** break in a **different**
test than the worker's — flipped
`checker_test.clj:757` (`rolling-upgrade-verdict-decisions`) on
scratch branch `claude/version-matrix-brief-luiof0-r17-breakprobe` —
and dispatched the workflow twice myself (`scenarios=none,
time-limit=120`):

| My dispatch | Ref | Conclusion | harness-tests |
|---|---|---|---|
| run 10, [31241373759](https://github.com/hooji/ratis-jepsen/actions/runs/31241373759) | scratch @`76f7aa9` (break) | **failure** | **failure**, 40 s |
| run 11, [31241374552](https://github.com/hooji/ratis-jepsen/actions/runs/31241374552) | worker head @`f67dfe3` | success | success, **29 s** |

The break run's `harness-tests` log carries exactly my plant:

```
FAIL in (rolling-upgrade-verdict-decisions) (checker_test.clj:757)
all five rolled and back -> valid
REVIEW 17 DELIBERATE BREAK — never merge
…
test-runner: summary {:test 115, :pass 994, :fail 1, :error 0, :type :summary}
##[error]Process completed with exit code 1.
```

while `build-sut`, `test (none)` and `red-gate` in the same run all
**succeeded** — so the workflow-level red is attributable to the test
job alone, which is simultaneously the review-brief probe's
composition check (one failed job ⇒ overall run red, even with every
other job green). The test job **can** fail, fails for the right
reason, and its failure is the workflow's failure.

Runtime claim verified: 29 s green / 40 s break wall against the
claimed 30–39 s.

### Emphasis 4 — native-vs-container divergence

Same suite, same counts, both paths, measured by me (115/995 in
`selftest`; 115/995 in my CI dispatch). Toolchain comparison:

| | container (image) | CI (`harness-tests`) |
|---|---|---|
| Clojure CLI | 1.12.1.1550 (Dockerfile ARG) | 1.12.1.1550 (workflow, hardcoded) |
| JDK | Ubuntu OpenJDK 21.0.11 | Temurin 21 |
| Deps resolution | `harness/deps.edn` → Maven | identical file → Maven |
| SUT jar | `mvnw -q -DskipTests install` | identical command |

What could drift: the CLI pin is **maintained in two places** with
only a comment tying them (finding 2) — a bump in one place would go
unnoticed until behavior differs; and the JDK vendors differ (both
major 21; the suite exercises no vendor-specific surface). What
catches drift today: the CI path is exercised on every dispatch
(loud); the container path only when someone runs `selftest` — so a
container-only breakage would be discovered at the next manual
`selftest`, not by CI. That asymmetry is acceptable for a test-tooling
path (the *suite content* cannot diverge — one `deps.edn`, one
`test/` tree, one runner), and the alternative (running the suite
inside the topology in CI too) would forfeit the job's 30 s cheapness.
Suggestion 2 makes the pin drift-proof for one line.

### Emphasis 5 — git/gnuplot; scenario regression

On the rebuilt image (`up` from the worktree, 1 m 43 s wall with the
lazyfs stage cached; image 786 MB → **858 MB**, +72 MB for
git + gnuplot-nox — no size or build-time surprise):

```
$ env/run.sh test --nemesis partition --time-limit 60
Everything looks good! ヽ('ー`)ノ        exit 0
```

1500 ops, 1081/419/0, every checker `:valid? true`, store well-formed
— **and carrying `latency-raw.png`, `latency-quantiles.png`,
`rate.png`**, the first plots produced inside the env image. Shim
interaction verified from the live container after that full run:
`/usr/local/bin/git` **does not exist** (the pre-existing
`command -v git ||` guard short-circuited, exactly as its comment
always promised — "No-op once the image grows real git"), `command -v
git` → `/usr/bin/git`, git 2.43.0, and jepsen's provenance logging
ran against real git without incident. gnuplot 6.0 in-image.

### Emphasis 6 — documented prerequisites, followed literally

From a clean `env/run.sh up` (fresh maven-repo volume — my Review 12
cycle's volume was removed by its `down`), I ran `harness/README.md`'s
two lines exactly:

```
env/run.sh up          # once
env/run.sh selftest    # SUT jar install + all unit and integration tests
```

→ 41 s wall including cold dependency resolution, exit 0, 115/995.
The capstone's original complaint (documented command fails in the
shipped environment) is dead: no host prerequisite beyond Docker was
used. The README's native-host section states JDK 21 + Clojure CLI —
exactly what the CI job (the native path) installs and needs.

### Emphasis 7 — ownership

8 files changed vs merge-base `3915d1a`, all inside
`harness/** env/** .github/workflows/jepsen.yml jobs/17-…/17_report.md`.
`README.md`, `LICENSE`, `NOTICE`, `docs/**` untouched (Job 16's).
Apache-2.0 header on the new `test_runner.clj`; no committed
artifacts; the workflow diff is precisely the one new job block
(verified line-by-line; YAML parses; no existing job touched). The
break/revert pair (`5d25b87`/`22718c0`) is kept in-branch, correctly
labeled TEMPORARY, with the revert applied — final tree is the working
code.

### The deviation (in-repo runner vs Maven-published)

Checked the worker's claim rather than accepting it: cognitect's
test-runner is distributed via git tag only (its README says to use it
as a git dep; no artifact at its coordinates on Clojars/Central), so
the brief's "preferred route" as literally written was unavailable.
The chosen shape — plain `clojure.test` + `org.clojure/tools.namespace`
1.5.1 from Maven Central, ~65-line runner readable in one screen —
satisfies the brief's actual requirement (classpath resolves with no
git) with less third-party surface than adopting an unmaintained fork.
Deviation justified and properly flagged.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking | `harness/test/ratis_jepsen/test_runner.clj:44` (`test-namespaces`) | A test file whose **first form is unreadable** (truncated/reader-broken `ns` decl) is silently dropped by `tools.namespace` discovery: suite stays green at the old count, exit 0. Measured — and measured **identically on the old cognitect runner** (same parse), so this is a pre-existing limitation of parse-based discovery, not a regression. The "discovered N namespaces" line is the only tell. Cheap hardening: fail when the count of `.clj` files under `test/` (minus known non-ns files) ≠ discovered namespaces. |
| 2 | non-blocking | `.github/workflows/jepsen.yml:340` / `env/Dockerfile:137` | The Clojure CLI pin `1.12.1.1550` is maintained in two places, tied only by a comment ("matches the env image's pin"). Nothing fails if they drift. One-line fix: the CI step can `grep -q "CLOJURE_CLI_VERSION=1.12.1.1550" env/Dockerfile` before installing (or read the ARG out of the Dockerfile). |

## Required revisions

None — verdict is MERGE.

## Suggestions (non-blocking)

- Finding 1's cross-check (~5 lines in `test-namespaces` or `-main`):
  compare discovered namespace count against the `.clj` file count
  under `test/`; a mismatch is a wiring failure, exit 1.
- Finding 2's pin assertion, or extracting the version into a single
  file both consumers read.
- `run.sh selftest` silently ignores extra arguments
  (`selftest) cmd_selftest ;;` — no shift, no complaint); same nit
  class as Review 12's `probe` finding. Reject unknown args.
- The worker's `push`-trigger proposal is sound at the measured 29–40 s
  cost; my runs corroborate the runtime evidence it cites.
- Endorse the worker's flagged-but-left stale lazyfs cost comment in
  the workflow (capstone §3.6) for the next docs pass.

## Verification notes

- Environment accommodations, all uncommitted: slim 6-cert proxy CA via
  `RJ_EXTRA_CA_BUNDLE` (same as Reviews 08–12). Nothing else — notably,
  the git/SUT-jar accommodations my Review 12 needed to run the suite
  in-container are now obsolete: **that is precisely this job working.**
- My scratch branch `claude/version-matrix-brief-luiof0-r17-breakprobe`
  (one clearly-labeled TEMPORARY commit, never merged) may still exist
  on origin: branch deletion failed through this session's git proxy
  (delete-only pushes hang up; the REST fallback is policy-blocked
  with 403). I retried at review end; if it remains, the coordinator
  should delete it — its head commit message says exactly what it is.
- Worker worktree removed after review; topology `down` run.
