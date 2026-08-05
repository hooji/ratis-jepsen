# Review 06 report — Job 06: CI workflow + env polish + public README (M1, infra side)

Worker PR: #11 (`claude/ci-env-polish-wbdv67`, head `4234fdc`, base
`473e6a3`). Reviewed in a detached worktree at the PR head. Docker
29.3.1, x86_64; sandbox egress accommodations as in Reviews 04/05
(slim CA bundle + `--network host` build args, both via the env's own
knobs) — notably, this review needed **no JVM-truststore seeding**,
because the PR's multi-cert fix makes that Review-04 workaround
obsolete (verified below).

## Verdict: MERGE

## Justification

Every acceptance criterion reproduced. The workflow is
`workflow_dispatch`-only (no cron — the automatic-REVISE tripwire is
absent), lints clean under actionlint v1.7.7 + shellcheck 0.9.0, and
its red-gate is sound in **both** directions — verified live on the
worker's green dispatch, again on my own dispatch of the final PR
head, and by replaying the step's script against stubbed outcomes
(seeded-run-exits-0 and exit-nonzero-without-conviction both fail
loudly; no inverted assert). The worker's linked run checks out
end-to-end on provenance and logs. All four env-polish items work as
claimed — including the multi-cert `EXTRA_CA_B64` split, which I
regression-tested with the exact five-cert bundle that exposed the
JVM-truststore gap in Review 04: all five certs now land in the JVM
store and `validate.sh` passes with no manual trust seeding. The
README's quickstart executes literally and every claim in it is
currently true. The two beyond-the-four env changes (`StrictModes no`;
the stale usage-text fix) are documented deviations with sound
rationale — the first is what makes the CI deliverable function at
all on hosted runners, the second closes Review 04's finding 2.
Findings are non-blocking notes.

## What I verified

### Criterion 1 — lint

```
$ actionlint -color .github/workflows/jepsen.yml && echo clean
clean        # actionlint 1.7.7, shellcheck 0.9.0 integration active
```

### Criterion 2 — dispatch evidence, three ways

**(a) The worker's linked run, provenance-checked**: run
[30976650548](https://github.com/hooji/ratis-jepsen/actions/runs/30976650548)
is `event: workflow_dispatch`, `head_branch` = the PR branch,
`head_sha 9bab897` — and `git diff 9bab897 4234fdc` touches only the
job report, so the run exercised exactly the content under review.
Conclusion success, 6 m 48 s, four jobs green with step timings
matching the report (`build-sut` 41 s; `test (none)` 1 m 17 s;
`test (partition)` 5 m 58 s; `red-gate` 2 m 54 s). The red-gate job
log contains the claimed evidence verbatim: `Analysis invalid!`,
`red-gate: harness exit code 1`, `':valid? false' evidence found in:`,
and `:failures [0 1 2 3 4]`; the `store-red-gate` artifact uploaded
(253 168 bytes, matching the reported 253 KB).

**(b) My own dispatch on the PR head** (doubling as the probe): run
[31013279875](https://github.com/hooji/ratis-jepsen/actions/runs/31013279875)
with `scenarios="none, typo"` (note the space) and `time-limit=60`:

- Input parsing: the space was trimmed; matrix = `test (none)` +
  `test (typo)`.
- `test (typo)` failed in 14 s with a fully comprehensible error —
  `Failed to validate "--nemesis typo": Must be one of none, partition`
  → exit 254 — no hung matrix; its `if: always()` path handled the
  absent store gracefully ("no store/ directory …", upload warns).
- `fail-fast: false` proven live: the typo failure cancelled nothing;
  `test (none)` ran to green (harness 51 s, exit 0) and `red-gate`
  passed again — a second, independent red-gate proof on the final
  head. The run's overall red X is the probe's intended shape.

**(c) Failure-direction simulation** (the review's emphasis 1): the
red-gate step's script, replayed verbatim against stubbed harness
outcomes:

```
case 1: seeded run exits 0        -> ::error:: … no longer catches the planted bug; STEP_EXIT=1
case 2: exit 1, no conviction     -> ::error:: … not a checker conviction (infrastructure failure?); STEP_EXIT=1
case 3: exit 1 + :valid? false    -> evidence listed; STEP_EXIT=0
```

A broken detector cannot rot silently, and an infrastructure failure
cannot masquerade as a catch.

**Bootstrap story verified**: run #1 (`push` event on the temporary
trigger, cancelled seconds in) and run #2 (the failed dispatch that
exposed StrictModes, all three topology jobs timing out on ssh) exist
exactly as narrated; the final tree's `on:` block is
`workflow_dispatch` only. Once this PR merges, the workflow is
registered on `main` and the dance is never needed again.

### Workflow hygiene (emphasis 3)

Verified in the file and live: scenarios parsed via `jq` from `env`
(injection-safe; trims, drops blanks, dedupes with a stated rationale;
empty list → documented default); `time-limit` validated as digits
with empty→300 normalization; `fail-fast: false`; per-scenario +
red-gate store uploads under `if: always()` with `sudo tar` for the
root-owned store (the failure path exercised in run #2 and in my typo
leg); `timeout-minutes` 20/60/30; exit-code propagation
harness→run.sh→step observed at 0, 1, and 254; concurrency group
per workflow+ref with `cancel-in-progress: false`;
`permissions: contents: read`; no cron.

### Criterion 3 — the four env-polish items, each demonstrated

**(a) Multi-cert `EXTRA_CA_B64` — the Review-04 regression case.**
Built with the same **five**-cert Anthropic bundle whose JVM-side loss
I found in Review 04 (the old code imported none of a multi-cert file
into the JVM store):

```
control$ ls /usr/local/share/ca-certificates/
ratis-jepsen-extra-ca-01.crt … ratis-jepsen-extra-ca-05.crt   # split 5 ways
control$ keytool -cacerts -list | grep ratis-jepsen            # all five:
debian:ratis-jepsen-extra-ca-01.pem … -05.pem, trustedCertEntry
  (Owners: the 5 Anthropic egress/inspection CAs, verified via -v)
```

The live proof is criterion 4's `validate.sh` pass: the in-control
Maven build's TLS verified through the JVM store populated **only**
by this fix — the manual keytool seeding my previous two reviews
required is no longer needed.

**(b) Pre-flight.** All three failure modes, instructive and immediate
(script exit 1 verified):

```
226 KiB system bundle -> "…is 228445 bytes, over the 65536-byte cap… 'Argument list too long'…"
PKCS12 file           -> "…contains no PEM certificate (…DER input?). Convert with: openssl x509 …"
missing file          -> "…is not a readable file"
```

(The oversized case is precisely the cryptic `docker build` failure I
hit in Review 04 before slimming the bundle by hand.)

**(c) Trap summary.** Forced failure — `docker kill ratis-jepsen-n3`
during validate's build step:

```
validate: FAIL: command exited 255 during: step: install tarball at /opt/ratis-kv on n1 n2 n3 n4 n5
validate: FAIL: at line 53: docker compose -f "${ENV_DIR}/docker-compose.yml" "$@"
validate: (servers not started yet; no node logs to dump)      [exit 255]
```

Step attribution + failing command + the servers-not-started note,
matching the report's excerpt (including the documented cosmetic
`BASH_COMMAND`-names-the-wrapper limitation). `fail()`-path check
semantics unchanged in the diff.

**(d) `maven-repo` note** present in `env/README.md` and accurate to
the compose file's volume lifecycle.

### Criterion 4 — no regressions

On the polished env, fresh cycle: `run.sh up` (7 nodes ssh-ready) →
`validate.sh` → **`validate: ALL CHECKS PASSED`, exit 0** →
`run.sh test --nemesis none --time-limit 60` → exit 0, "Everything
looks good!" → `run.sh down` → `docker ps -a` empty.

**StrictModes verified from both sides**: `StrictModes no` present in
the image's sshd drop-in; key material deliberately `chown -R
1001:1001`-ed (the GitHub-runner uid) → `ssh root@n1` from control
still succeeds. Run #2's timeout-shaped failure plus run #3's green
cover the before/after on real runners.

### Criterion 5 — README truth-check, executed literally

Quickstart run as written on the worktree: `env/run.sh up` →
`env/run.sh test --nemesis partition --time-limit 300` → **exit 0,
"Everything looks good!"** → `env/run.sh down` → clean. (No
known-issue false-red occurred in this run; the signature check was
armed per the review brief but not needed.) Every referenced path
exists (`docs/RUNS.md`, `docs/PLAN.md`, `docs/DESIGN.md`,
`docs/PROCESS.md`, `env/README.md`, `sut/ratis-kv/`, `harness/`,
`env/`); the badge targets
`actions/workflows/jepsen.yml` (= the workflow's path); the M0-complete
and M1-in-progress claims are current; "test builds the SUT tarball on
first use" matches run.sh's ensure step. The worker's Known-gaps note
that the badge may show "no status" until a first `main` sweep is
accurate.

### Criterion 6 — headers, ownership, artifacts

7 files, +747/−20: `.github/workflows/jepsen.yml` (new, Apache-2.0
header), root `README.md`, `env/{Dockerfile,README.md,run.sh,validate.sh}`,
and the job report — exactly the ownership list. `run.sh` diffed
hunk-by-hunk: the CA pre-flight + constant + one usage-comment line;
**`cmd_test` untouched** (interface frozen as required).
`validate.sh`: trap/step machinery only; check semantics unchanged.
No artifacts in the diff; worktree `git status` clean after all runs.
Worker report has every `jobs/README.md` section; the report's
evidence matched everywhere I recomputed it.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking (accepted deviation) | `env/Dockerfile:75-90` | `StrictModes no` is a fifth env change beyond the four polish items — but the CI deliverable cannot function without it (run #2's evidence), the rationale comment is security-honest (the check defends multi-user hosts; this compose network is single-purpose and throwaway), it's Deviations-documented, and I verified both the 1001-owned-key success and that local behavior is unchanged. |
| 2 | non-blocking | `.github/workflows/jepsen.yml:187` | Red-gate's evidence grep accepts `:valid? false` in *any* results.edn under `store/` — correct today (runner workspaces start empty; the seeded run is the only store) and self-documented in the worker's Known gaps; tighten to the seeded run's own directory if the job ever runs in a reused workspace. |
| 3 | non-blocking | branch history | The bootstrap commits (empty nudge; push-trigger add/remove) are noise that squash-merge erases; the final tree is clean. Recorded so nobody mistakes run #1's `push` event for a live trigger. |
| 4 | non-blocking (coordinator-scoped) | repo root | The public README declares Apache-2.0 but the repo has no `LICENSE`/`NOTICE` file (PLAN Q17 intended headers-from-day-one *and* a LICENSE). Outside this job's ownership; the worker's suggestion to add both at coordinator level deserves fast-tracking now that the repo is public with donation intent. |
| 5 | non-blocking (observation) | Actions tab | My probe dispatch (run #4) is intentionally red (`test (typo)` exit 254); its green `test (none)` + green `red-gate` on the final head are the useful signal. |

## Suggestions (non-blocking)

- Second the worker's whole suggestion list, with emphasis on
  `LICENSE`/`NOTICE` (Finding 4) and, post-Job-05-merge, the scenarios
  default update — the matrix needs no workflow change for new
  nemesis names (verified by the parse design), so that is one input
  default edit.
- The green-side symmetry idea (assert `:valid? true` evidence in
  green legs) pairs naturally with the Review-05 revision landing on
  `main` — once merged, a green leg's silent `:unknown` would be the
  only remaining blind spot; cheap to close then.
- When the coordinator first dispatches from `main`, use
  `scenarios=none,partition` and expect the badge to go green on that
  run — nothing else is needed to light it up.
