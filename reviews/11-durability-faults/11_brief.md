# Review 11 — durability faults via lazyfs (worker PR #23)

*Coordinator brief, 2026-08-06.* **Read `reviews/README.md` first.**
Standard: `jobs/11-durability-faults/11_brief.md` + documented
deviations. Requires Docker. Two of this PR's claims are headed for
external consumption (the metadata result, and the fault-arming
workaround) — verifying them is the review.

## Emphasis

1. **The metadata result — a negative finding, held to positive-claim
   standards.** The report concludes `term`/`votedFor` reach stable
   storage before the node acts on a recorded vote, from source plus a
   609-sample experiment. Verify both halves independently: re-read the
   3.2.2 source path yourself (write → sync → act ordering, and where
   the sync actually happens), then re-run the probe script and
   confirm its methodology can distinguish the two outcomes at all —
   i.e. demonstrate the probe would detect a regression if one existed
   (invert the condition, stub the sync, or otherwise show the
   experiment has power). A probe that cannot fail proves nothing.
   Then assess the parent-directory-sync caveat on its merits: is it
   genuinely outside the model Ratis promises, or a real gap worth an
   upstream note? Your judgement here decides whether it enters the
   backlog as a candidate.
2. **Did `torn-write` actually tear a write?** The pinned lazyfs
   silently ignores fifo torn-ops carrying `occurrence=`, so the
   worker arms the fault at runtime with next-write semantics. Verify
   from node evidence that a partial write genuinely landed on the
   target file before the restart, and that the loud startup refusal
   is caused by that tear — not by an unrelated startup failure or by
   the arming mechanism itself. Confirm the majority stayed
   linearizable through the window. Also confirm the two lazyfs
   defects are real and characterized accurately (they belong in our
   backlog as upstream-candidates for *that* project).
3. **`unsync-drop-all` sizing — narrow, but not vacuous.** The run is
   pinned to specific parameters because whole-cluster faults generate
   long-lived concurrent `:info` operations that exhausted the checker
   twice. Verify the analysis of *why* (the `:info` population is
   inherent to the fault shape, not a mapping defect), confirm the
   pinned shape still exercises a real fault window, and check the
   preserved stores support the account. Then probe the boundary: does
   a modestly larger shape still complete, or is the pinning right at
   the edge?
4. **The evidence law and the off-path**: mount proven per node;
   negative arm (unmounted ⇒ distinct error, run fails) reproduced;
   and the regression check — existing scenarios behave as before when
   `--durability` is off (run one non-durability gate and compare
   against the ledger).
5. **Expectations discipline**: confirm the report states expectations
   per scenario in advance and that each outcome is judged against the
   stated expectation — including the out-of-model boundary from
   BACKLOG item 4 (scenarios must not discard state unequally across
   nodes and then convict).
6. **Crossover run**: counter workload under `unsync-drop` — exactly-once
   still holds, retry counts nonzero.
7. **Costs and diffs**: startup budget per run reported honestly;
   `env/` image diff confined to the pinned lazyfs install; workflow
   diff itemized; no `sut/**` changes.

## Probe (≥1)

Run `torn-write` twice more and report whether the outcome is stable
(clean recovery vs loud refusal are both legal — but the *distribution*
matters for what we would claim publicly); or apply `unsync-drop` to a
node while it is receiving an install-snapshot (compound storage fault
— does anything surprising surface?).

Deliver `reviews/11-durability-faults/11_report.md`; verdict PR
`Review 11: <verdict>`; self-merge if report-only.
