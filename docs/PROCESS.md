# Development process — jobs, reviews, merges

*Adopted 2026-08-04 (owner-specified protocol + coordinator mechanics).
This is the working agreement for how code gets into `main`.*

## Roles

- **Owner** (repo owner): assigns jobs and reviews to agent sessions,
  arbitrates when the process stalls, holds final authority.
- **Coordinator** (a dedicated agent session): partitions work into jobs,
  writes job and review briefs, answers REVISE outcomes, performs all
  merges into `main`, maintains `docs/`. The coordinator writes **no
  implementation code** — otherwise review would be self-review.
- **Workers** (one agent session per job): implement exactly one job per
  its brief, on an auto-assigned branch, and submit a PR.
- **Reviewers** (one agent session per review): independently verify one
  job's PR against its brief and submit a verdict report.

Workers and reviewers are separate sessions; a session never plays both
roles for the same job.

## Artifacts and layout

```
jobs/README.md                     # general instructions for all workers
jobs/<NN>-<slug>/<NN>_brief.md     # job brief (coordinator)
jobs/<NN>-<slug>/<NN>_report.md    # job report (worker, in the job PR)
reviews/README.md                  # general instructions for all reviewers
reviews/<NN>-<slug>/<NN>_brief.md  # review brief (coordinator)
reviews/<NN>-<slug>/<NN>_report.md # review report (reviewer, in the review PR)
```

- `NN` is a zero-padded job number; the review of job `NN` reuses `NN` and
  the slug. Revision rounds append files, never rewrite:
  `<NN>_revision_1.md` (coordinator's distilled fix list, if needed),
  `<NN>_report_r1.md` (reviewer's re-review), etc.
- Every brief begins by directing the agent to read the relevant README
  first, then `docs/PLAN.md` and `docs/DESIGN.md`.

## Lifecycle of a job

1. **Brief.** Coordinator writes `jobs/NN-slug/NN_brief.md` and pushes it
   directly to `main` (briefs, reviews/README, docs are coordinator-owned
   and merge without review).
2. **Assignment.** When a brief is ready, the coordinator hands the owner
   a kickoff prompt of the form
   `Please look to jobs/NN-slug/NN_brief.md for your instructions.` —
   the owner pastes it into a fresh worker session. The Claude Code
   harness auto-assigns the branch name; the worker bases it on current
   `main`.
3. **Implementation.** Worker implements within the brief's declared file
   ownership, adds `NN_report.md`, opens a PR to `main` titled
   **`Job NN: <brief title>`**, and leaves it open.
4. **Review brief.** Coordinator writes `reviews/NN-slug/NN_brief.md`
   (usually short: pointer to the job brief + any review emphasis),
   pushes to `main`, and hands the owner the kickoff prompt
   (`Please look to reviews/NN-slug/NN_brief.md for your instructions.`).
5. **Review.** Reviewer, on their own auto-branch off `main`, discovers
   the worker PR through their GitHub tooling (title `Job NN:`), verifies
   independently (see `reviews/README.md`), writes `NN_report.md` with a
   verdict —
   **MERGE** or **REVISE** — plus justification, and opens a PR titled
   **`Review NN: <verdict>`**.
6. **Merge.** The reviewer squash-merges their *own* review-report PR
   directly (allowed because it only adds files under
   `reviews/<NN>-<slug>/` — if it touches anything else, it waits for
   the coordinator). Then the coordinator:
   - on **MERGE**: squash-merges the worker PR into `main` as one commit
     (`Job NN: <title> (#<pr>)`), then updates any affected docs;
   - on **REVISE**: relays the reviewer's numbered revision list to the
     worker's PR (comment and/or `NN_revision_1.md`); the worker session
     continues on the *same branch/PR*; the same reviewer re-reviews the
     delta. **After two REVISE rounds, stop: owner + coordinator decide**
     (re-scope, reassign, or coordinator documents an accepted exception).

### Standing jobs (added 2026-08-06, first used by Job 13)

Some jobs are **standing**: re-run after milestones merge, more than
once, against a moving repository. They keep one brief and one directory,
and each run adds its own report (`NN_report.md`, then `NN_report_2.md`,
…) — never overwriting a previous one. A documentation-only standing job
is **merged by the coordinator without a separate review**, which makes
its report the only record of what was examined; the bar for care is
correspondingly higher, not lower. Everything else about the lifecycle
(brief, ownership, branch, PR titled `Job NN: <title> (<n>)`) is
unchanged.

## Tooling note

Worker and reviewer sessions run in **Claude Code Cloud** environments:
GitHub operations (listing/inspecting/opening PRs) go through the
**GitHub MCP server** tools available in those sessions — there is no
`gh` CLI there. Plain `git` (fetch, worktree, show) is available for
bringing branches into the workspace. The coordinator runs locally on
the owner's machine and uses `gh` directly.

## Branch and merge rules

- `main` is coordinator-merge-only by convention, with one exception:
  a reviewer squash-merges their own **report-only** review PR (files
  under `reviews/<NN>-<slug>/` exclusively). Workers and reviewers
  otherwise never push to `main` or to each other's branches.
- Worker branches base on the `main` current at assignment. If `main`
  moves (another job merged), the coordinator handles trivial conflicts at
  merge; non-trivial conflicts become a revision request.
- Squash-merge for job PRs (one commit per job on `main`, report
  included); review PRs likewise.
- **Round-2 fast path** (owner-approved 2026-08-04): when a revision is
  mechanical, reviewer-prescribed, and carries a pre-specified
  acceptance check, the coordinator may — at the owner's direction —
  merge the worker PR while the round-2 re-review is still in flight.
  The re-review still binds: a surprise REVISE triggers an immediate
  fix-forward revision (or a revert if fix-forward is unsafe). Never
  applies to first-round reviews; the worker branch is kept until the
  verdict lands.
- Run artifacts (`store/`, `target/`, caches) never enter PRs — see
  `.gitignore`; reference runs are summarized in `docs/RUNS.md`, not
  committed.

## Parallelism

Briefs declare **File ownership** (paths the job may create/modify) and
**Parallel-safe with** (job numbers). The coordinator only marks jobs
parallel when ownership is disjoint; the owner may then assign them
simultaneously. Anything undeclared is serial by default.

## Standards that apply to every job

- Apache-2.0 license headers on all source files (PLAN Q17; the ASF
  donation endgame makes retrofitting expensive).
- Reports are written for a reader who was not present: actual commands
  run, actual output excerpts, deviations from the brief stated plainly.
- A worker who discovers the brief conflicts with reality (API mismatch,
  impossible acceptance criterion) implements the smallest reasonable
  interpretation *or* stops early — either way the report's Deviations
  section tells the story. Guessing silently is the only wrong move.
