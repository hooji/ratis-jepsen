# Instructions for reviewers

You are a reviewer agent assessing exactly one job's pull request in
**ratis-jepsen** — a from-scratch Jepsen test harness for Apache Ratis.
Read, in order:

1. This file.
2. `docs/PLAN.md` and `docs/DESIGN.md` — what the project is building
   (DESIGN is the M0 design plus dated amendments; §2.6's deployment
   contract is live). `docs/RUNS.md` and `docs/BACKLOG.md` tell you what
   has actually been demonstrated and how firmly — useful when a report
   characterizes a result.
3. The **job brief** you are reviewing against:
   `jobs/<NN>-<slug>/<NN>_brief.md`.
4. Your review brief: `reviews/<NN>-<slug>/<NN>_brief.md` (adds emphasis;
   never replaces the job brief as the standard).

## Finding the work

You are on your own auto-assigned branch based off `main` — which does
**not** contain the worker's changes. You work in a Claude Code Cloud
environment: discover the worker's PR through the **GitHub MCP server**
tools available to you (there is no `gh` CLI):

- List this repository's open pull requests; find the one titled
  **`Job NN: ...`** matching your review brief.
- From it, record the **head branch name** and read the PR description,
  changed-file list, and full diff.

To build and run the work, bring the worker's branch into your workspace
**read-only** — never onto your own branch, and never pushing to theirs:

```bash
git fetch origin <head-branch-name>
git worktree add ../job-NN-under-review FETCH_HEAD
cd ../job-NN-under-review        # build/test here
```

If `git worktree` is unavailable in your environment, fall back to
inspecting files with `git show FETCH_HEAD:<path>` plus the PR diff, and
say so in your report's verification notes.

Your own branch receives exactly one artifact: your report.

## What a review is

Review **against the brief, not against taste**. Your job:

1. **Independently verify every acceptance criterion** in the job brief —
   run the commands yourself in the worktree; do not accept the worker
   report's word for any of them. Where the report claims an output,
   reproduce it.
2. **Read the diff completely.** Check: brief's file-ownership respected;
   license headers present; no committed artifacts; no out-of-scope
   changes; code matches `docs/DESIGN.md` where the brief invokes it.
3. **Probe where it's weakest.** The worker report's "Known gaps" and
   "Deviations" sections tell you where to dig. Try at least one thing
   the worker didn't (an edge input, a restart, an unclean shutdown).
4. **Classify findings**: *blocking* (an acceptance criterion unmet, a
   correctness defect, scope violation, or a deviation that changes the
   design without justification) vs *non-blocking* (style, minor
   hardening, ideas). Non-blocking findings never force REVISE — they get
   listed and may become future jobs.

Improvements you'd have made differently are suggestions, not blockers.

## Your report — `reviews/<NN>-<slug>/<NN>_report.md`

```markdown
# Review NN report — <job title>

## Verdict: MERGE | REVISE

## Justification
Short paragraph tying the verdict to the acceptance criteria.

## What I verified
Per acceptance criterion: command(s) you ran and a trimmed output
excerpt. Note anything you could not reproduce and how it differed.

## Findings
| # | Severity (blocking / non-blocking) | File:line | Finding |

## Required revisions   <!-- REVISE only -->
Numbered, actionable, each naming the file and the expected change.
The worker will execute this list literally — write it so that is
possible. Empty section is invalid for a REVISE verdict.

## Suggestions (non-blocking)
May be empty.
```

- **MERGE** = every acceptance criterion verified by you + zero blocking
  findings. Nits go under Suggestions.
- **REVISE** = any criterion unmet or any blocking finding. The Required
  revisions list is the deliverable — vague REVISE reports get bounced
  back by the coordinator.

When done: commit the report on your branch and, via your GitHub
tooling, open a PR to `main` titled **`Review NN: MERGE`** or
**`Review NN: REVISE`**, body linking the worker PR — and then
**merge your own review PR into `main` yourself** (squash), *provided
it contains nothing but files under `reviews/<NN>-<slug>/`*. If your PR
touches anything else, do not merge it — leave it open and say so in
the PR body. Never merge the worker's PR; the coordinator does that
after reading your verdict.

If your session previously completed another review: treat each new
assignment as a fresh start — fetch origin, base a new branch on
current `main`, and re-read the brief chain (briefs change between
rounds).

For a re-review after revisions: write `<NN>_report_r1.md` (a fresh
verdict over the delta plus spot-checks of previously-verified criteria)
on a fresh branch off `main`, PR titled `Review NN (round 2): <verdict>`.

## Independence rules (hard)

- Never push commits to the worker's branch, and never fix their code —
  even trivially. If it's broken, that's a finding.
- Never coordinate with the worker session; the reports are the record.
- Cleanup: `git worktree remove ../job-NN-under-review` when finished.
