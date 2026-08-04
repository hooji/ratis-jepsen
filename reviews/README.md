# Instructions for reviewers

You are a reviewer agent assessing exactly one job's pull request in
**ratis-jepsen** — a from-scratch Jepsen test harness for Apache Ratis.
Read, in order:

1. This file.
2. `docs/PLAN.md` and `docs/DESIGN.md` — what the project is building.
3. The **job brief** you are reviewing against:
   `jobs/<NN>-<slug>/<NN>_brief.md`.
4. Your review brief: `reviews/<NN>-<slug>/<NN>_brief.md` (adds emphasis;
   never replaces the job brief as the standard).

## Finding the work

You are on your own auto-assigned branch based off `main` — which does
**not** contain the worker's changes. Discover them via the open PR:

```bash
gh pr list --state open          # find the PR titled "Job NN: ..."
gh pr view <pr> --json title,headRefName,body
gh pr diff <pr>                  # the full change
```

To build and run the work, check out the worker's branch **read-only in a
separate worktree** — never on your own branch, and never push to theirs:

```bash
git fetch origin <headRefName>
git worktree add ../job-NN-under-review FETCH_HEAD
cd ../job-NN-under-review        # build/test here
```

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

When done: commit the report on your branch and open a PR to `main`
titled **`Review NN: MERGE`** or **`Review NN: REVISE`**, body linking
the worker PR. Do not merge anything; the coordinator merges both PRs.

For a re-review after revisions: write `<NN>_report_r1.md` (a fresh
verdict over the delta plus spot-checks of previously-verified criteria)
on a fresh branch off `main`, PR titled `Review NN (round 2): <verdict>`.

## Independence rules (hard)

- Never push commits to the worker's branch, and never fix their code —
  even trivially. If it's broken, that's a finding.
- Never coordinate with the worker session; the reports are the record.
- Cleanup: `git worktree remove ../job-NN-under-review` when finished.
