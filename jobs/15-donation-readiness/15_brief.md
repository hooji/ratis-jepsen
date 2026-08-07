# Job 15 — donation-readiness assessment (capstone, adversarial)

*Coordinator brief, 2026-08-07.*

**Read `jobs/README.md` first for the mechanics** (branch, PR, report
format) — then ignore the rest of this repository's internal
documentation until the point this brief tells you to read it. Your
value is that you have not seen this project before, and the first
half of this job depends on keeping it that way.

## Your role

You are a committer on **Apache Ratis**. An outside party you do not
know has approached the project offering to donate a Jepsen test
harness for Ratis — this repository — and has asked whether the
project wants it. You have been asked to evaluate the offer.

You are busy. You maintain a widely-deployed consensus library with a
small active committer base. Every artifact the project accepts is
something someone must understand, run, fix, and answer questions
about for years. You have seen well-intentioned contributions that
cost more than they returned. You are not hostile, but you are deeply
skeptical, and you are under no obligation to be encouraging.

**Your job is to find what is wrong with this before the project
commits to it.** Praise costs the requester nothing and teaches them
nothing. A blunt, specific, well-evidenced objection is the most
valuable thing you can produce. If your assessment reads as broadly
positive, you have probably not looked hard enough — go look again at
the things that would embarrass the project six months from now.

## How to approach it (order matters)

**Phase 1 — outside in, as a stranger.** Start where a maintainer
would: the front page. Follow only what it offers you. Form your first
impressions before you know anything about how the thing was built:
Is it clear what this is within thirty seconds? Does it tell you what
it found? Do its claims sound calibrated or promotional? Would you
keep reading, or close the tab?

**Phase 2 — does it work?** Follow the setup and run instructions
exactly as written, from a clean checkout, as a newcomer would. Do not
fix problems you hit — record them; a maintainer who hits them will
not fix them either. Run at least one scenario end to end if the
environment permits. If it does not permit, say so precisely and
assess what a maintainer with a normal Linux box would experience.

**Phase 3 — is the evidence real?** The repository publishes committed
run results. Interrogate them: do the verdicts match the artifacts? Do
the runs actually exercise what they claim (the harness asserts things
like "install-snapshot really happened" — verify that machinery is
sound rather than self-certifying)? Are results labeled honestly,
including the ones that are supposed to fail and any that were voided?
Could a reader be misled by a skim?

**Phase 4 — the findings.** The project claims observations about
Ratis itself. For each: is it real, is it correctly characterized, is
its severity fairly stated, and is the distinction maintained between
"defect", "already fixed", and "open question"? Spot-check at least
the strongest claim against the actual Ratis source. If any claim
overstates, that is a headline finding of yours — over-claiming
against the project you are offering to help is disqualifying in a way
that a missing feature is not.

**Phase 5 — the burden.** Only now read whatever internal
documentation you want. Then answer the question that actually decides
donations: **what would the Ratis project be signing up for?** Stack,
dependencies, CI cost, expertise required, how it fails when it fails,
what happens when Ratis changes underneath it, and who fixes it when
the donor loses interest. Be specific about the skills a Ratis
committer would need that they may not have.

## Assess at minimum

- **Identity**: what this repository *is*, in your words, and whether
  it succeeds at being that. Name the gap if the thing it claims and
  the thing it is differ.
- **Does it test Ratis, or does it test its own toy server?** The
  system under test is a small KV server written for this project.
  Interrogate whether results about it generalize to Ratis — this is
  the first question a maintainer will ask, and the honest answer may
  be partial.
- **Coverage vs. the claim.** What does it genuinely exercise, what
  does it not, and does the repository state its own blind spots
  where a reader will find them?
- **Correctness of the machinery** itself: the failure classification,
  the checkers, the evidence assertions. A harness that convicts a
  correct system, or clears a broken one, is worse than none.
- **Credibility of the published results**, including reproducibility
  by someone else.
- **Provenance and licensing** for an ASF context: headers, third-party
  code, anything that would complicate an IP grant. Note anything that
  needs disclosure before an offer is made — including how the work was
  produced, if you judge that material to a maintainer's decision.
- **Documentation quality** for its actual audiences: a first-time
  user, a maintainer deciding, a future contributor.
- **What would make you say no**, and separately, **what would make you
  say yes**.

## Your report

`jobs/15-donation-readiness/15_report.md`. Write it as the response you
would actually send to the person offering the donation — direct,
technical, unsoftened. Include:

1. **Verdict**, chosen deliberately: *accept as offered* / *accept with
   required changes* / *not in its current form* / *decline*. Justify it
   in a paragraph.
2. **Required before the offer is made** — numbered, specific,
   actionable. These are the things we will fix before contacting the
   project. Order them by what would most damage the project's
   reception.
3. **Would-strengthen-the-case** items, separately.
4. **Findings** with evidence: what you checked, what you ran, what you
   read, what you found. Quote and cite.
5. **Your honest read of the reception**: if this arrived on the Ratis
   dev list tomorrow, what happens? What is the most likely objection
   from someone who has not read it carefully, and what is the most
   likely objection from someone who has?
6. **Anything you could not evaluate**, and what it would take.

Do not soften findings to be collegial, and do not pad the report with
things that are fine. If something is genuinely good, one line is
enough; spend your words on what is not.

## File ownership

`jobs/15-donation-readiness/15_report.md` only. **Change nothing else**
— you are assessing, not fixing. Anything you would change goes in the
report as a recommendation. **Parallel-safe with: none.**

## Process

No review job follows this one; the owner and coordinator read your
report directly and decide what to act on. Open a PR titled
`Job 15: donation-readiness assessment`.
