# Job 14 — published reference runs (results committed to the repository)

*Coordinator brief, 2026-08-06.*

**Read `jobs/README.md` first — binding, except where this brief
explicitly overrides it (see "Standing rules this job overrides").**
Then `docs/PLAN.md`, `docs/DESIGN.md`, `docs/RUNS.md`,
`docs/BACKLOG.md`, and the current `README.md`. Base on current `main`
(all milestones merged; documentation refreshed by Job 13).

## Purpose

Someone who opens this repository should be able to see **what the
harness actually found**, immediately, without installing Docker or
running anything. Today the evidence of our runs lives in ledger prose
and in stores that were never committed. This job produces the
artifact that closes that gap: two dated, version-labelled directories
of real run output, each with a README that a stranger can read and
understand, linked from the front page.

This is a *publication* job. Its output will be read by people
evaluating whether this harness is worth their trust — including, in
due course, Apache Ratis maintainers. Accuracy and legibility matter
more than volume, and a result that looks bad but is true is worth
more than a tidy one that isn't.

## Standing rules this job overrides — read carefully

`jobs/README.md` instructs workers never to commit run artifacts, and
`.gitignore` excludes `store/`. **For this job only, and only under
the results directories defined below, committing run output is the
deliverable.** Constraints on how you do it:

- **Do not weaken the global `store/` ignore.** It protects every
  future run from accidental commits. Publish under a *different*
  path (`results/…`, below) that is not ignored, copying the artifacts
  you select out of `store/`. If a path still trips an ignore rule,
  add a narrowly-scoped negation for the results tree only — never a
  blanket change.
- Everything else in `jobs/README.md` stands: ownership, headers where
  applicable, honest reporting, no scope creep.

## What to run

The full breadth of what the harness supports, against **both**
versions, plus the runs that prove the harness itself works.

1. **Determine the scenario list from the repository, not from this
   brief** — you know the current capability surface better than I do
   (register and counter workloads; the partition/crash/pause/mixed
   family; snapshot churn; membership; transfer; the durability
   family; follower-read modes; mixed-version). List what you chose
   and why in your report, and name anything you deliberately skipped.
2. **Both versions**: 3.2.2 and the 3.3.0 release-candidate artifacts.
   Per Review 12, the RC is **not a released version** — see naming
   rules below; this constraint is absolute.
3. **Include the runs that are supposed to fail**: the seeded-bug red
   gate, and the Q14 retry-cache-boundary demonstration. These are the
   proof that a green run means something. They must be unmistakably
   labelled as expected-red (see "Integrity rules").
4. **Mixed-version runs** belong in the newer version's directory,
   clearly marked as spanning both.
5. **Prefer CI for anything the workflow can dispatch**, and cite the
   public run URL — a reader who can follow a link to a run on neutral
   infrastructure needs less faith than one reading our transcripts.
   Run locally whatever CI can't cover, and say plainly which runs
   were which.

## Where the results go

```
results/
├── README.md                          # short index: what these are, how to read them
├── <YYYY-MM-DD>-ratis-3.2.2/
│   ├── README.md                      # the summary for this version (see below)
│   └── <scenario>/…                   # selected artifacts per run
└── <YYYY-MM-DD>-ratis-3.3.0-rcN/      # exact RC label — never bare "3.3.0"
    ├── README.md
    └── <scenario>/…
```

Use the actual date the runs were performed. If runs span midnight,
pick one date and say so.

## Per-version README (the main deliverable)

Each version directory's README must stand alone for a reader who has
never seen this project. Include, in whatever structure reads best:

- **What was tested**: the version and its exact artifact provenance
  (for the RC: where the artifacts came from and how they were
  verified — Job 12's report is your source), the harness commit, the
  environment (topology, node count, CI vs local, runner/host class),
  Jepsen and lazyfs versions, and the date.
- **A results table**: one row per run — scenario, workload, duration,
  verdict, and *for expected-red runs, the expected verdict alongside
  the actual one*. Link each row to its artifacts, and to the CI run
  where applicable.
- **What the run proved**, in plain prose: what faults were injected,
  what the checkers verified, and — critically — **what the evidence
  assertions confirmed** (install-snapshot events actually occurred,
  configuration changes actually committed, retries actually happened,
  the durability mount was actually in the path). A green run whose
  evidence assertion counted zero would be meaningless; say that these
  didn't.
- **Known limits of these runs**: durations, op counts, what is *not*
  covered (the harness's blind spots are documented across
  `docs/`; summarize honestly rather than implying completeness).
- **Anomalies**: anything surprising, any run that needed repeating,
  any result that disagrees with `docs/RUNS.md`.

## Artifact selection and size discipline

Committed artifacts are permanent repository weight; be deliberate.
Per run, prioritise in this order:

1. The checker results / summary output (always).
2. Evidence-assertion output and any custom checker detail (always).
3. The Jepsen log for the run (compress if large).
4. The history (compress; it is the raw evidence and worth keeping if
   it fits the budget).
5. Timeline/latency visualisations (nice; drop first if space is
   tight).
6. Per-node server logs — **excerpts only**, unless a run is
   interesting enough to justify more (e.g. an expected-red run's
   convicting evidence).

Target roughly **≤ 50 MB per version directory**, and state your
actual totals. Compress large text with gzip. Whatever you exclude,
say so in the directory README — "we kept X, dropped Y" is honest;
silently thinning is not.

## Integrity rules (non-negotiable)

- **Expected-red runs must be impossible to misread.** Label them at
  every level they appear: filename or directory name, the results
  table, and the surrounding prose. A casual reader skimming the table
  must not come away thinking the harness failed.
- **Do not retry a run until it goes green.** If a run behaves
  unexpectedly, run it again *and publish both outcomes* with your
  analysis. Selective publication of the flattering run is the single
  worst thing this job could do.
- **If a run convicts unexpectedly** (a green-config run reporting a
  violation): stop, preserve everything, triage harness-vs-SUT-vs-
  Ratis per established practice, and report. Do not publish a
  conviction you have not explained, and do not suppress one.
  Depending on what you find, the coordinator may hold this job's
  merge — that is the correct outcome, not a failure of the job.
- **Version labels**: the RC is a release candidate everywhere it
  appears — directory names, tables, prose, file names. No surface may
  read as a released 3.3.0.

## Main README update

Add a prominent, early link to `results/` — a reader should reach the
evidence within one click of the front page. Two or three sentences of
framing (what the runs are, when, which versions, what expected-red
means) and pointers into each version directory. Keep the rest of the
README's structure and voice as Job 13 left it; do not rewrite it.

## File ownership

`results/**` (new), `README.md` (the results link and its framing
only), `.gitignore` (only a narrowly-scoped negation if strictly
required), `docs/RUNS.md` (append ledger entries for these runs, in
the established style), `jobs/14-reference-runs/14_report.md`.
Nothing else — no source, no workflow, no SUT changes. If a run
reveals something that needs a code change, report it rather than
fixing it. **Parallel-safe with: none.**

## Acceptance criteria

1. Both version directories exist, correctly named and dated, with
   the runs you listed in your report.
2. Each per-version README is complete per the section above and reads
   correctly standalone.
3. `results/README.md` index exists and orients a first-time reader.
4. Expected-red runs are labelled at every level; a skim of any
   results table cannot mislead.
5. Evidence-assertion outcomes are quoted, not summarized away.
6. Size totals stated; exclusions stated.
7. Main README links to the results within its first screenful.
8. `docs/RUNS.md` appended; `.gitignore`'s global `store/` protection
   intact.
9. Your report per `jobs/README.md`, including: the full run list with
   verdicts, CI-vs-local split with URLs, anything repeated and why,
   and anything you could not run.

## Merge process for this job (no review)

Like Job 13, this job is merged without a separate review cycle: the
output is generated evidence plus prose, and the owner merges it
directly. Consequently **your report is the only account of what was
run and what was left out** — write it so that someone auditing the
published results a year from now can tell whether they were
cherry-picked. They were not; make that legible.

## Non-goals

New scenarios, code changes, upstream filing, the capstone assessment
(a separate job follows this one), performance benchmarking.
