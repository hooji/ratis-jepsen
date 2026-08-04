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
2. **Assignment.** Owner assigns the job to a worker session. The Claude
   Code Desktop harness auto-assigns the branch name; the worker bases it
   on current `main`.
3. **Implementation.** Worker implements within the brief's declared file
   ownership, adds `NN_report.md`, opens a PR to `main` titled
   **`Job NN: <brief title>`**, and leaves it open.
4. **Review brief.** Coordinator writes `reviews/NN-slug/NN_brief.md`
   (usually short: pointer to the job brief + any review emphasis) and
   pushes to `main`. Owner assigns a reviewer session.
5. **Review.** Reviewer, on their own auto-branch off `main`, discovers
   the worker PR (`gh pr list`, title `Job NN:`), verifies independently
   (see `reviews/README.md`), writes `NN_report.md` with a verdict —
   **MERGE** or **REVISE** — plus justification, and opens a PR titled
   **`Review NN: <verdict>`**.
6. **Merge.** Coordinator:
   - always merges the review-report PR (both verdicts are audit trail);
   - on **MERGE**: squash-merges the worker PR into `main` as one commit
     (`Job NN: <title> (#<pr>)`), then updates any affected docs;
   - on **REVISE**: relays the reviewer's numbered revision list to the
     worker's PR (comment and/or `NN_revision_1.md`); the worker session
     continues on the *same branch/PR*; the same reviewer re-reviews the
     delta. **After two REVISE rounds, stop: owner + coordinator decide**
     (re-scope, reassign, or coordinator documents an accepted exception).

## Branch and merge rules

- `main` is coordinator-merge-only by convention. Workers and reviewers
  never push to `main` or to each other's branches.
- Worker branches base on the `main` current at assignment. If `main`
  moves (another job merged), the coordinator handles trivial conflicts at
  merge; non-trivial conflicts become a revision request.
- Squash-merge for job PRs (one commit per job on `main`, report
  included); review PRs likewise.
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
