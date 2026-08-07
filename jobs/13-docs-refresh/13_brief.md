# Job 13 — documentation refresh (standing, re-runnable)

*Coordinator brief, 2026-08-06.*

**Read `jobs/README.md` first — binding.** Then this brief.

**This is a standing job**: it is run after milestones merge, more than
once, against a moving repository. Each run gets its own report file
(`13_report.md`, then `13_report_2.md`, `13_report_3.md`, …) — never
overwrite a previous one. **This job is documentation-only and is
merged without review**, so the bar for care is *higher*, not lower:
nothing you write may outrun what the repository can back up.

## The task

Bring every prose document in the repository into agreement with what
the repository actually contains and has actually demonstrated. The
top-level `README.md` is known to be stale (it was written at M0 and
the project has since completed M1–M4 and more); assume others have
drifted too, and verify rather than trust that assumption in either
direction.

## Method (do this in order)

1. **Establish ground truth first, from the repository — not from the
   documents.** Read the merged job and review reports in `jobs/` and
   `reviews/` (they are the project's factual record), `docs/RUNS.md`
   (what has actually been run), the workflow file (what CI actually
   offers), and the source tree (what actually exists: nemeses,
   workloads, checkers, CLI options, scenarios). Build yourself a
   short inventory before you edit anything.
2. **Then audit each document against that inventory**, listing every
   discrepancy you find: `README.md`, `docs/PLAN.md`,
   `docs/DESIGN.md`, `docs/PROCESS.md`, `docs/BACKLOG.md`,
   `docs/RUNS.md` (structure/consistency only — do not invent or
   alter run results), `env/README.md`, `harness/README.md`,
   `jobs/README.md`, `reviews/README.md`, `CLAUDE.md`.
3. **Fix what is stale, wrong, or missing**, preserving each
   document's existing voice and structure. Historical decision
   records (PLAN's Q&A, DESIGN's amendments, PROCESS's rules) are
   *history*: correct them only where they misstate the present, and
   when a decision has since changed, amend in the established style
   (dated amendment, prior decision preserved) rather than rewriting
   the past.
4. **Do not create new documents** unless a gap genuinely has no home
   in an existing one; if you do, say why in your report.

## Standards for the refreshed docs

- **Every claim must be checkable from the repository.** No implied
  capabilities, no aspirational phrasing, no numbers you did not
  verify. Where a document must characterize a result, use the
  precise framing the corresponding review settled on (for example:
  results that are "source-proven; probe-consistent" must not become
  "experimentally confirmed").
- **The README is the front door** and, per `docs/PLAN.md`, this
  repository is intended to be offered to the Apache Ratis project.
  Write it for a Ratis maintainer who has never seen the repo: what
  this is, what it tests and how (workloads, nemeses, checkers,
  including the durability and version dimensions), how to run it
  locally and in CI, what it has demonstrated to date, what its known
  limits are, and where the detailed documents live. Accurate and
  plain beats impressive.
- **Findings discipline.** Where docs mention observations about
  Ratis, they must reflect `docs/BACKLOG.md`'s classifications exactly
  — a *question with a mechanism* must not be presented as a found
  defect, and an unfixed observation must not be presented as
  resolved. If the backlog itself is stale relative to the reports,
  fix the backlog too and say so.
- Status statements ("M4 complete", scenario lists, supported
  versions) must match the merged reality at the commit you are
  working from.

## File ownership

Every `*.md` in the repository **except** `jobs/**/[0-9]*_brief.md`,
`reviews/**/*.md`, and previously-merged `jobs/**/*_report.md` — those
are the historical record and are immutable. You add exactly one new
file: your own report. No source, config, or workflow changes; if you
find a defect that is not a documentation problem, report it instead
of fixing it. **Parallel-safe with: none** (it touches shared docs).

## Acceptance criteria

1. A discrepancy inventory in your report: what you found stale,
   per document, with the evidence you checked it against.
2. Each discrepancy fixed, or explicitly deferred with a reason.
3. `README.md` reflects the current state and reads correctly for a
   first-time external reader.
4. Cross-document consistency: status claims, scenario/workload lists,
   version support, and findings classifications agree everywhere they
   appear.
5. Links and paths in the docs resolve (check them; broken references
   are the most common decay in a repo this size).
6. Your report states plainly what you did **not** change and why.

## Report

`jobs/13-docs-refresh/<report-file>.md` per `jobs/README.md`'s format,
with the inventory as the "What was built" equivalent. Open a PR
titled `Job 13: documentation refresh (<n>)`. The coordinator merges
without a separate review, so your report is the only record of what
was examined — make it specific.
