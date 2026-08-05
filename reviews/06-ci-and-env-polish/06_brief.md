# Review 06 — CI + env polish + README (worker PR: `Job 06: …`)

*Coordinator brief, 2026-08-04.* **Read `reviews/README.md` first.**
Standard: `jobs/06-ci-and-env-polish/06_brief.md` (note: manual
dispatch ONLY — any cron/schedule trigger present is an automatic
REVISE item). Requires Docker for the env regression checks.

## Baseline

Ownership is sharp here: `.github/**`, `env/**` (the four BACKLOG-7
polish items only — `run.sh test`'s interface frozen), root
`README.md`, report. Diff `env/run.sh` hunk-by-hunk against that
constraint. Headers, artifacts, report completeness.

## Emphasis

1. **Red-gate logic, both directions.** The job must pass iff the
   seeded run fails. Verify the failure direction too: reason through
   (and if you can, demonstrate on the PR branch) what happens if the
   seeded run unexpectedly *succeeds* — the red-gate job must then
   FAIL loudly, not skip or pass. Inverted-assert bugs here would let
   a broken detector rot silently.
2. **The dispatch evidence.** The worker's report should link a real
   workflow run from their branch (green `build-sut` +
   `test[none]` + `test[partition]`, red-gate behaving). Verify the
   linked run's provenance (their head SHA) and read its logs. If the
   worker couldn't dispatch, attempt it yourself via your GitHub
   tooling on the PR branch ref; if you also can't, verdict may still
   be MERGE on inspection quality but say plainly that first dispatch
   happens post-merge (the coordinator will run it).
3. **Workflow hygiene**: scenarios-input parsing into the matrix
   (quoting/whitespace), `fail-fast: false`, per-scenario store upload
   on failure paths (`if: always()`), `timeout-minutes`, exit-code
   propagation from `run.sh test` through docker exec/ssh layers,
   concurrency group, no cron.
4. **Env polish scope + regression**: each of the four items
   demonstrated (two CAs through `EXTRA_CA_B64`; forced failure shows
   the `trap` summary; size pre-flight; maven-repo README note); then
   a full local regression: `run.sh up` → `validate.sh` → `run.sh test
   --nemesis none --time-limit 60` → `down`, all green.
5. **README truth-check**: execute the quickstart literally as
   written on a clean checkout; every claim (M0 status, commands,
   badge target) must be currently true.

## Known issue on `main` (do not re-triage)

Review 05 discovered an intermittent **false-red** in the outcome map
that ships on current `main` (writes graded `:fail` on
NotLeaderException can actually have committed via a deposed leader's
surviving entries). It reproduces ~1-in-3 under `mixed`; it is *rare
but possible* under plain `partition`. The fix is in flight on Job
05's PR. If one of your `partition` runs convicts the cluster: check
for that signature (convicted key's history shows a `:fail
:not-leader-or-not-ready` write of the "impossible" value) and, if it
matches, record it as the known issue — it is not a Job 06 defect and
must not drive your verdict. Reference:
`reviews/05-nemesis-breadth/05_report.md`.

## Probe (≥1)

Dispatch with a bogus scenario name (`--scenarios "none,typo"`) — the
failure must be comprehensible, not a hung matrix; or corrupt the
tarball artifact path expectation and confirm `build-sut`→`test`
wiring fails loudly.

Deliver `reviews/06-ci-and-env-polish/06_report.md`, verdict PR
`Review 06: <verdict>`, self-merge if report-only.
