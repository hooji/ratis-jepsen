# Job 06 — CI workflow + env polish + public README (M1, infra side)

*Coordinator brief, 2026-08-04.*

**Before anything else, read `jobs/README.md` — it is binding.** Then
`docs/PLAN.md` (Q7: CI on GitHub-hosted runners; the repo is now
**public**, minutes are free), `docs/DESIGN.md` §4, `docs/BACKLOG.md`
item 7, and this brief. Base your branch on current `main` (M0
complete). Job 05 runs in parallel in `harness/**` — you must not
touch that path; today's valid scenarios are `none` and `partition`
(Job 05 adds more; design for that without depending on it).

## Deliverables

1. **`.github/workflows/jepsen.yml`**:
   - Triggers: **`workflow_dispatch` only** — inputs `scenarios`
     (comma-list, default `none,partition`), `time-limit` (default
     300). **No cron/scheduled trigger** (owner decision 2026-08-04):
     cadence stays manual until the planned donation offer to the
     Apache Ratis project, whose maintainers would set their own
     schedule.
   - Job `build-sut`: `sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml -q
     package`, upload the tarball as an artifact.
   - Job `test` — **matrix: one runner per scenario** (parse the
     `scenarios` input): download tarball, `env/run.sh up`,
     `env/run.sh test --nemesis <scenario> --time-limit <input>`,
     propagate exit code, **always** upload compressed `store/**`
     (retention 7 days), `timeout-minutes: 60`.
   - Job `red-gate` (the CI-encoded test-of-the-test, runs in every
     sweep): a short seeded run (`--seed-bug stale-reads`,
     `--nemesis partition`, 120 s) that **passes iff the harness
     fails** — assert non-zero harness exit and `:valid? false`
     evidence; its store uploads too.
   - Concurrency group per ref; jobs independent (`fail-fast: false`).
2. **Env polish** (BACKLOG item 7, all four): multi-cert
   `EXTRA_CA_B64` split; image/bundle size pre-flight; `trap`-based
   failure summary in `validate.sh`; `env/README.md` note on the
   `maven-repo` volume lifecycle. Nothing else in `env/` changes
   semantics — `run.sh test`'s interface is frozen (CI depends on it).
3. **Root `README.md`** (replace the stub): what this project is (a
   from-scratch Jepsen harness for Apache Ratis), status (M0 complete:
   harness catches a seeded linearizability bug, green on healthy
   clusters — link `docs/RUNS.md`), quickstart (`run.sh up` /
   `test` / `down`), pointers (`docs/PLAN.md`, `docs/DESIGN.md`,
   `docs/PROCESS.md`), CI badge for the workflow, Apache-2.0 note.
4. **`jobs/06-ci-and-env-polish/06_report.md`** per `jobs/README.md`.

## File ownership

May create/modify: `.github/**`, `env/**` (items above only — not
`run.sh test`'s interface), root `README.md`,
`jobs/06-ci-and-env-polish/06_report.md`. **Not** `harness/**` (Job 05,
parallel), not `sut/**`, not `docs/**`.

**Parallel-safe with: Job 05.**

## Acceptance criteria (command + output excerpt each)

1. Workflow lints clean (`actionlint` if available in your
   environment; otherwise `gh api` schema-check alternative or a
   documented offline validation — say which).
2. **A real dispatch of the workflow from your branch** via your
   GitHub tooling (`workflow_dispatch` accepts a `ref`): green
   `build-sut` + `test[none]` + `test[partition]`, red-gate passing
   (i.e., seeded run failed as required). Link the run in your report.
   If your session's permissions cannot dispatch, say so plainly and
   deliver the validated workflow with a hand-off note — the owner
   dispatches before review.
3. Each env-polish item demonstrated (e.g. two CAs through
   `EXTRA_CA_B64`; forced failure showing the `trap` summary).
4. `env/validate.sh` and a local `run.sh up`/`test --nemesis none`
   still green after the polish (no regressions).
5. README renders correctly (check on your branch via the GitHub UI
   tooling); badge points at the workflow.
6. Headers, ownership, report per `jobs/README.md`.

## Non-goals

New nemeses or checkers (Job 05), scenario defaults beyond
`none,partition` (coordinator updates the default list after Job 05
merges), **any scheduled/cron trigger**, lazyfs/FUSE spike (separate,
gates M4), branch protection or repo settings, performance.
