# Job 16 — pre-offer presentation, licensing, and provenance

*Coordinator brief, 2026-08-07.*

**Read `jobs/README.md` first — binding.** Then
`jobs/15-donation-readiness/15_report.md` (the capstone assessment
this job executes — read §2 items 1–3 in full; they are your
specification), then `README.md`, `docs/BACKLOG.md`, `docs/PROCESS.md`.
Base on current `main`.

## Context

An adversarial capstone review, written in the role of an Apache Ratis
committer receiving this repository as a donation offer, returned
*accept with required changes*. Three of its five required items are
presentation, licensing and disclosure — this job. (Item 4, test
runnability, is Job 17, running in parallel; item 5, upstream filing,
is the owner's.)

Nothing here is a code change. All of it is what a maintainer sees
before they evaluate any code, which is why the capstone ranked these
above the engineering.

## Deliverables

1. **Fix the false upstream-filings claim** (capstone §2.1). The
   README's "Findings about Ratis" section states that no upstream
   issues have been filed from this work. That is false:
   [RATIS-2640](https://issues.apache.org/jira/browse/RATIS-2640) was
   filed 2026-08-04 by this repository's owner — from the evaluation
   work that preceded this harness — and is Resolved/Fixed; our own
   `harness/src/ratis_jepsen/client.clj` and `docs/BACKLOG.md`
   reference it. Replace the sentence with the accurate, better story:
   what was filed, when, by whom, its outcome, and — stated separately
   and precisely — the current filing status of the findings *this
   harness* produced. Check the whole repository for the same claim
   repeated elsewhere and fix every instance.
2. **Add `LICENSE` and `NOTICE` at the repository root** (capstone
   §2.2; `docs/BACKLOG.md` has carried this since 2026-08-05). LICENSE:
   the standard Apache-2.0 text. NOTICE: this project's attribution,
   plus credit for studied prior art (sofa-jraft-jepsen — shape only,
   no code reuse, as the README already states) and any third-party
   material a reader should know about. Close the backlog item.
3. **Add a provenance section to the README** (capstone §2.3), placed
   where a maintainer will find it rather than buried: who produced
   this work (one human owner directing named AI agent sessions), how
   (the job/brief/independent-review process — link `docs/PROCESS.md`),
   and what human verification the results received. Be plain and
   unapologetic; the capstone's judgement is that disclosure reads as
   confidence and discovery reads as concealment. Do not oversell the
   process either — describe it accurately and let it stand.
4. **State what is being offered.** The repository now contains ~13k
   lines of internal process record (`jobs/`, `reviews/`) against ~10k
   lines of code. A donation offer should say whether the offer is the
   harness and results, or that plus the process record. Draft the
   statement in the README (a sentence or two in the provenance or a
   "status of this repository" note); the owner will finalize the
   wording at offer time. Flag it in your report as owner-confirmable.
5. **Two cheap accuracy fixes from the capstone's second list**:
   mention the corporate-proxy CA knob in the Quickstart so a
   TLS-inspected network's first failure is explained rather than raw
   (capstone §3.5 — the knob exists and works; only the Quickstart is
   silent), and correct any stale cost/claim comments the capstone
   identified in documentation you own.

## File ownership

`README.md`, `LICENSE`, `NOTICE`, `docs/**`,
`jobs/16-pre-offer-presentation/16_report.md`. **Not** `harness/**`,
`env/**`, `.github/**`, `sut/**` — Job 17 owns those in parallel.
**Parallel-safe with: Job 17.**

## Acceptance criteria

1. Every instance of the upstream-filings claim is accurate; quote the
   old and new text in your report, and list where you searched.
2. LICENSE and NOTICE present and correct; backlog item closed with a
   reference to this job.
3. Provenance section present, discoverable (say where you put it and
   why), and accurate — no claim about the process that
   `docs/PROCESS.md` and the merged record don't support.
4. Offer-scope statement drafted and flagged as owner-confirmable.
5. Quickstart proxy note present; stale claims you own corrected.
6. No factual drift introduced: any statement you touch must remain
   consistent with `docs/RUNS.md`, `docs/BACKLOG.md` classifications,
   and the results READMEs (the capstone verified these; do not break
   them).
7. Report per `jobs/README.md`.

## Non-goals

Code, workflow, or environment changes (Job 17); filing anything
upstream (owner's); re-running anything; restructuring the README
beyond the additions above.

## Note

This job is merged without a separate review. The capstone is your
reviewer in advance — its §2 items are specific and evidenced, so
implement them faithfully rather than reinterpreting them, and say so
in your report if you believe one is wrong.
