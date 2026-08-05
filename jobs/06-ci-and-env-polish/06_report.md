# Job 06 report — CI workflow + env polish + public README (M1, infra side)

## Summary

The repo now has its first CI: `.github/workflows/jepsen.yml`, a
`workflow_dispatch`-only sweep (no cron, per the owner's donation-intent
decision) that builds the SUT tarball once, fans out one runner per
scenario parsed from a comma-list input, always uploads compressed
`store/` artifacts, and runs a **red-gate** job on every sweep — a seeded
`stale-reads` run that passes only if the harness fails it with
`:valid? false` evidence, so every sweep re-proves the harness can catch
bugs. All four BACKLOG-item-7 env polish items landed (multi-cert
`EXTRA_CA_B64` split, CA-bundle size pre-flight, `trap`-based failure
summary in `validate.sh`, `maven-repo` lifecycle note), and the root
README stub was replaced with a real front page. The two decisions to
look hardest at: the scenario matrix is computed from the input by a
parse step inside `build-sut` (so the valid-scenario set lives only in
the harness, ready for Job 05's additions), and the red-gate asserts
*both* non-zero harness exit *and* a `:valid? false` results.edn (a mere
infrastructure failure cannot masquerade as a catch).

## What was built

- `.github/workflows/jepsen.yml` — new: the manual-dispatch sweep
  (`build-sut` → matrix `test` per scenario + `red-gate`; concurrency
  group per ref, `fail-fast: false`, `timeout-minutes` 60/30, store
  artifacts always uploaded, 7-day retention).
- `env/Dockerfile` — the `EXTRA_CA_B64` block now splits the decoded PEM
  bundle one-cert-per-file before `update-ca-certificates`, because
  Debian's `ca-certificates-java` hook imports only the *first* cert of
  each file into the JVM keystore (Review 02 finding 2); fails the build
  loudly on a certificate-free bundle.
- `env/run.sh` — `preflight_ca_bundle()` validates `RJ_EXTRA_CA_BUNDLE`
  (readable, contains a PEM cert, ≤ 64 KiB) with instructive errors
  before the content becomes a docker build-arg (finding 3); stale
  "stub until Job 04" usage text corrected.
- `env/validate.sh` — `set -E` + ERR trap: any abort that bypasses
  `fail()` (node death between checks, build error, ssh drop) now prints
  the current step, the failing command and line, and per-node log tails
  once servers have started (finding 4). Step/check announcements go
  through a `step()` helper that records the current step; no check
  semantics changed.
- `env/README.md` — `RJ_EXTRA_CA_BUNDLE` row rewritten for the new
  multi-cert + pre-flight semantics; new paragraph documenting the
  `maven-repo` volume lifecycle (fast within an up-cycle, removed by
  `down`, hermetic across cycles); stale `test` stub line corrected.
- `README.md` (root) — replaced the one-line stub: what the project is,
  M0 status with the `docs/RUNS.md` pointer, quickstart
  (`up`/`test`/`down`), CI section, pointers table, CI badge for the
  workflow, Apache-2.0 note.
- `jobs/06-ci-and-env-polish/06_report.md` — this report.

## How it was verified

All local runs were executed in this session's sandbox (Docker Engine
29.3.1, Compose v5.1.1, x86_64). The sandbox's container egress is
TLS-inspected, so — exactly as in Review 02 — every `up`/`validate`
invocation set `RJ_EXTRA_CA_BUNDLE`; here that bundle was deliberately
**two** certificates (TLS-inspection CA + egress-gateway CA), which the
old single-file code could not fully trust (only the first cert reached
the JVM keystore) and which therefore exercises the fix end-to-end on
every run below.

### Criterion 1 — workflow lints clean

`actionlint` was not preinstalled; I fetched the pinned v1.7.7 release
binary and ran it with shellcheck 0.9.0 installed, so actionlint's
embedded shellcheck pass over all `run:` blocks was active:

```
$ actionlint -color .github/workflows/jepsen.yml && echo "actionlint: clean"
actionlint: clean
```

### Criterion 2 — real dispatch from the branch

Dispatched via the GitHub MCP tooling on ref `claude/ci-env-polish-wbdv67`
with `scenarios=none,partition`, `time-limit=300` (a one-time
registration bootstrap was needed first — see Deviations):

- Run: <https://github.com/hooji/ratis-jepsen/actions/runs/30976079997>
  (run #2, event `workflow_dispatch`, head `3c82fb1` — the final,
  dispatch-only workflow)
- RUN-VERDICT-PLACEHOLDER (run in flight as of this commit; verdict and
  per-job evidence land in the follow-up commit)

### Criterion 3 — each env-polish item demonstrated

**(a) Two CAs through `EXTRA_CA_B64`.** Built with a 2-cert bundle
(inspection CA first, gateway CA second — under the old code the second
would be missing from the JVM store):

```
$ docker compose ... exec -T control ls /usr/local/share/ca-certificates/
ratis-jepsen-extra-ca-01.crt   ratis-jepsen-extra-ca-02.crt
$ ... openssl x509 -noout -subject   # per split file
subject=O = Anthropic, CN = sandbox-egress-production TLS Inspection CA
subject=O = Anthropic, CN = sandbox-egress-gateway-production Egress Gateway CA
$ ... keytool -cacerts -storepass changeit -list | grep -i ratis-jepsen
debian:ratis-jepsen-extra-ca-01.pem, Aug 5, 2026, trustedCertEntry,
debian:ratis-jepsen-extra-ca-02.pem, Aug 5, 2026, trustedCertEntry,
$ ... keytool -list -v -alias <each> | grep Owner:
Owner: CN=sandbox-egress-production TLS Inspection CA, O=Anthropic
Owner: CN=sandbox-egress-gateway-production Egress Gateway CA, O=Anthropic
```

Both certs in both stores; the green `validate.sh` below (in-container
Maven downloads through the inspected egress) is the live proof the JVM
trust actually works.

**(b) Size/readability/PEM pre-flight.** Three failure modes, each a
friendly instruction instead of docker's `Argument list too long`:

```
$ RJ_EXTRA_CA_BUNDLE=/root/.ccr/ca-bundle.crt env/run.sh up   # 226 KiB system bundle
run.sh: ERROR: RJ_EXTRA_CA_BUNDLE=/root/.ccr/ca-bundle.crt is 226526 bytes, over the 65536-byte cap. It travels as a single docker build-arg, so base64-encoded it would exceed the kernel's ~128 KiB per-argument limit and docker build would fail with 'Argument list too long'. Pass only the CA certificate(s) your proxy chain actually needs, not a full system bundle — see the RJ_EXTRA_CA_BUNDLE notes in env/README.md.   [exit 1]

$ RJ_EXTRA_CA_BUNDLE=<pkcs12 file> env/run.sh up
run.sh: ERROR: ... contains no PEM certificate (no '-----BEGIN CERTIFICATE-----' line; DER input?). Convert with: openssl x509 -inform der ...   [exit 1]

$ RJ_EXTRA_CA_BUNDLE=/nonexistent.pem env/run.sh up
run.sh: ERROR: RJ_EXTRA_CA_BUNDLE=/nonexistent.pem is not a readable file   [exit 1]
```

**(c) Trap-based failure summary.** Two forced failures, both shapes
previously ended in a bare error with no context:

*Node killed before servers exist* (Review 02's probe 2 reproduced:
`docker kill ratis-jepsen-n3` during the build step; the install loop's
ssh then dies):

```
ssh: Could not resolve hostname n3: Name or service not known
validate: FAIL: command exited 255 during: step: install tarball at /opt/ratis-kv on n1 n2 n3 n4 n5
validate: FAIL: at line 53: docker compose -f "${ENV_DIR}/docker-compose.yml" "$@"
validate: (servers not started yet; no node logs to dump)
[exit 255]
```

*Abort between checks with servers running* (n5's pidfile removed after
check (a); checks (b)/(c) pass; check (d)'s unguarded `kill -TERM
$(cat pidfile)` fails):

```
cat: /var/run/ratis-kv.pid: No such file or directory
kill: usage: kill [-s sigspec | -n signum | -sigspec] pid | ...
validate: FAIL: command exited 2 during: check (d): SIGTERM stops all five servers (deadline 30s)
validate: FAIL: at line 53: docker compose -f "${ENV_DIR}/docker-compose.yml" "$@"
--- last lines of n1:/var/log/ratis-kv.log ---
2026-08-05 04:38:48.163 [...] GrpcLogAppender - n1@...->n4-AppendLogResponseHandler: follower responses appendEntries COMPLETED
[... 20-line tails for each of n1..n5 ...]
[exit 2]
```

An unplanned third demonstration happened organically: a `run.sh up`
invoked without the sandbox's CA bundle failed the image build, and
validate printed
`validate: FAIL: command exited 1 during: step: run.sh up` with the
"servers not started yet" note — the between-steps shape, straight from
real use.

**(d) `maven-repo` README note.** Added to `env/README.md` ("Network
note" section): persists `/root/.m2` across container recreations within
one up-cycle (why the second `validate`/`test` run is fast), removed by
`run.sh down --volumes` (why cycles stay hermetic).

### Criterion 4 — no regressions after the polish

```
$ RJ_EXTRA_CA_BUNDLE=<2-cert bundle> env/validate.sh
...
validate: PASS (a): startup line present on all five nodes
validate: PASS (b): exactly one node (n1) is currently LEADER
validate: PASS (c): port 6000 listening on all five nodes
validate: PASS (d): all five servers stopped cleanly on SIGTERM
validate: ALL CHECKS PASSED
VALIDATE_EXIT=0

$ env/run.sh up   # already up; then:
$ env/run.sh test --nemesis none
...
 :valid? true}
Everything looks good! ヽ('ー`)ノ
TEST_NONE_EXIT=0        # store: ratis-kv-register-none/20260805T043602.889Z
```

### Criterion 5 — README renders; badge points at the workflow

Fetched the branch's rendered blob page
(`github.com/hooji/ratis-jepsen/blob/claude/ci-env-polish-wbdv67/README.md`)
after pushing: H1 + badge image
(`.../actions/workflows/jepsen.yml/badge.svg`), all four H2 sections,
fenced quickstart block, and the pointers table with working relative
links all render; no broken markdown.

### Criterion 6 — headers, ownership, report

Apache-2.0 header comment on `jepsen.yml` (README files exempt per repo
convention, as established in Review 02). Diff touches only
`.github/**`, `env/**`, root `README.md`, and this report:

```
$ git diff --stat main...HEAD
 .github/workflows/jepsen.yml | 205 ++++++++++++++++++++++++++++
 README.md                    |  75 +++++++++-
 env/Dockerfile               |  24 ++--
 env/README.md                |  14 +-
 env/run.sh                   |  37 ++++-
 env/validate.sh              |  55 ++++++--
```

## Deviations from the brief

- **Stale "stub until Job 04 (exits 64)" wording** in `run.sh`'s header
  (which `usage()` prints) and `env/README.md`'s usage block described
  `test` as a stub that exits 64 — factually wrong since Job 04, and the
  new root README points users at `run.sh test`. I corrected the text in
  both places. This is outside the four named polish items but changes
  no behavior; flagging it per the scope rule rather than leaving known-
  wrong documentation in files this job owns.
- **actionlint was not preinstalled**; the brief anticipated this. I used
  the pinned official v1.7.7 release binary (with shellcheck present so
  the embedded shell checks ran) rather than a schema-check fallback.
- **Dispatching needed a one-time workflow-registration bootstrap.** The
  dispatch API resolves a workflow only once GitHub has *registered* it,
  which happens when the file reaches the default branch **or** the
  workflow has run at least once; a workflow pushed only to a feature
  branch returns 404 until then (GitHub community discussions #169535,
  #8140). The brief's "workflow_dispatch accepts a ref" is true — but
  only after registration. Fix, entirely within this job's ownership:
  one commit temporarily added a branch-scoped `push:` trigger (whose
  run — run #1 — was cancelled seconds after creation; it exists purely
  to register the workflow), the next commit removed it, and the real
  dispatch then succeeded against the final, dispatch-only file. Two
  incidental commits remain in branch history (plus one earlier empty
  "nudge" commit from diagnosing the 404); squash-merge folds them away.
  The owner does NOT need this dance ever again: after this PR merges,
  the workflow exists on `main` and is permanently registered.
- **While hardening for the bootstrap**, the parse step now normalizes
  an empty `time-limit` to the default 300, and downstream jobs consume
  the parse step's outputs rather than raw inputs — for dispatch events
  GitHub always supplies the declared defaults, so this only matters for
  non-dispatch invocation shapes, but it makes the workflow robust to
  them.

## Known gaps and risks

- **Trap summary's command attribution**: `BASH_COMMAND` in the ERR trap
  reports the innermost failing command — often the `compose()` wrapper
  (`docker compose -f ... "$@"`) rather than the `on_node` call site.
  The step name carries the useful context; cosmetic, documented here.
- **Red-gate evidence grep** accepts `:valid? false` in *any*
  `results.edn` under `store/`. On a hosted runner the workspace starts
  empty, so the only store present is the seeded run's; if the job were
  ever pointed at a reused workspace this check would need tightening to
  the seeded test's own directory.
- **Store artifact names embed the scenario string** (`store-<scenario>`).
  Artifact names reject some characters (`/`, `:`, ...); harness nemesis
  names are simple tokens today and the harness rejects unknown names
  before upload, but Job 05 should keep new nemesis names to
  `[a-z0-9-]`.
- **Badge state before merge**: the README badge reads the workflow's
  runs on the default branch; until this PR merges and a sweep runs
  from `main`, GitHub may render it as "no status". The badge URL itself
  is correct (verified by the render check).
- **Matrix order**: the parse step dedupes scenarios with jq `unique`,
  which sorts — matrix legs may not preserve the input's order. Purely
  cosmetic (jobs are parallel and independent).
- **arm64 remains execution-untested** for the image (unchanged claim
  from Job 02; nothing in this job is arch-pinned).
- **CI wall-clock estimate** (~10–20 min per test leg: image build
  ≈ 4–6 min, deps ≈ 2–3 min, 300 s workload + checking) is projected
  from local timings — RUN-TIMING-PLACEHOLDER (measured numbers land
  with the run verdict in the follow-up commit).

## Suggestions (out of scope)

- **Add `LICENSE` (and the PLAN-Q17 `NOTICE`) at the repo root.** The
  README now states Apache-2.0 and per-file headers exist, but the repo
  has no LICENSE file; for a public repo with donation intent that's a
  gap worth closing at coordinator level (file ownership kept it out of
  this job).
- After Job 05 merges, update the workflow input default (coordinator,
  per this brief's non-goals) — the workflow needs no other change; new
  scenario names flow straight into the matrix.
- Consider a green-side symmetry to red-gate: assert `:valid? true`
  evidence in each green leg's results.edn (today the exit code alone
  carries it).
- If sweep frequency ever grows, cache/prebuild the env image (GHCR or
  actions/cache) — deliberately not done now to keep the workflow free
  of registry coupling.
