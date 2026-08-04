# Instructions for job workers

You are a worker agent implementing exactly one job in **ratis-jepsen** — a
from-scratch Jepsen test harness for Apache Ratis (a Java Raft library).
Read, in order, before writing any code:

1. This file.
2. `docs/PLAN.md` — project purpose, decisions, milestones.
3. `docs/DESIGN.md` — the M0 design your job implements a slice of.
4. Your brief: `jobs/<NN>-<slug>/<NN>_brief.md`.

`docs/PROCESS.md` describes the overall workflow if you want the full
picture; the rules that bind *you* are below.

## Ground rules

- **The brief is the contract.** Its Deliverables and Acceptance criteria
  sections define done. Stay inside the brief's **File ownership** list —
  do not create or modify files outside it (exception: your report).
- **Scope discipline.** No drive-by refactors, no extra features, no
  "while I was here". If you see something worth fixing outside scope,
  note it in your report's Suggestions section instead.
- **If the brief conflicts with reality** (an API doesn't exist as
  described, an acceptance criterion is untestable), make the smallest
  reasonable interpretation and keep going, or stop early if it's
  fundamental — and either way, document exactly what you hit in your
  report's Deviations section. Never guess silently. You have no chat
  channel to the coordinator; **your report is the channel**.
- **Verify before you report.** Every acceptance criterion in the brief
  must be exercised by you, with the command and an output excerpt in the
  report. "Should work" is not a verification.
- Apache-2.0 license headers on every source file you create.
- Do not commit build or run artifacts (`target/`, `store/`, `.cpcache/`
  — see `.gitignore`).

## Branch and PR mechanics

- You are on an auto-assigned branch based off `main`. Stay on it. Never
  push to `main` or any other branch.
- Commit incrementally with clear messages (the PR will be squash-merged;
  history hygiene inside the branch is for the reviewer's benefit).
- When done: add your report (below), then open a PR to `main` titled
  **`Job NN: <the brief's title>`** with a body linking your brief and
  report. Leave the PR open — a separate reviewer session will examine
  it, and the coordinator performs the merge.
- If a revision round comes back (a numbered list in the PR or a
  `<NN>_revision_1.md` beside your brief): address each numbered item on
  the same branch, update your report (append a "Revision 1" section —
  don't erase the original), and note completion in the PR.

## Your report — `jobs/<NN>-<slug>/<NN>_report.md`

Required sections, in this order:

```markdown
# Job NN report — <title>

## Summary
Three-to-six sentences: what exists now that didn't before, and the one
or two decisions you made that a reviewer should look hardest at.

## What was built
File-by-file map of everything added/changed, one line each.

## How it was verified
Per acceptance criterion: the exact command(s) run and a trimmed excerpt
of real output. Include failure runs you fixed if they changed the design.

## Deviations from the brief
What the brief said vs. what you did vs. why. "None" if none.

## Known gaps and risks
What a reviewer or future job should know: untested edges, assumptions,
fragile spots.

## Suggestions (out of scope)
Anything worth a future job. May be empty.
```

Write it for a reader who was not in the room: no "as discussed", no
unexplained shorthand.
