# Review 09 — exactly-once counter workload (worker PR #19)

*Coordinator brief, 2026-08-06.* **Read `reviews/README.md` first.**
Standard: `jobs/09-counter-workload/09_brief.md` + documented
deviations (the `quorum-pause` nemesis is a legitimate
brief-vs-reality addition; verifying its rationale is emphasis 3).
Requires Docker.

## Emphasis

1. **The custom bounds checker is now load-bearing — attack it.**
   Verify the math (`:ok` exactly-once, `:info` 0-or-1, `:fail`
   exactly-zero) on fabricated histories including at least two
   adversarial shapes of your own (suggested: an `:info` add whose
   delta also appears through the apply-time pinning path; concurrent
   adds to one key where reads interleave mid-apply). Assess the
   pinning design: `ADD` replies carry the post-apply total — confirm
   the SM computes that under the apply lock (a true linearization
   witness) and the checker consumes it soundly. Note the deliberate
   property: `:fail`-must-be-zero means outcome-map unsoundness
   surfaces as conviction — confirm the green gates therefore actually
   certify the definite-fail rows under crash.
2. **Exactly-once greens, re-run**: counter under crash ×2 and
   mixed-all, with nonzero retry counts; the zero-retry evidence-law
   negative arm; register-workload no-regression.
3. **The Q14 result and its mechanism.** Reproduce the conviction
   (server window 500 ms, client delay 5 s, quorum-pause) and verify
   causation — the doubled deltas' retries met *expired* cache entries
   (retry-cache metrics/log evidence, not inference). Then verify the
   timeout-shaped-not-crash-shaped analysis that motivated
   `quorum-pause`: the worker's claims (append-to-reply span ~2 ms;
   a frozen leader is deposed before unread requests append; kill -9
   can't strand applied-unreplied ops in meaningful numbers) should be
   checked against source/observation — this analysis is headed for
   the L3 provider's design record, so its truth matters beyond this
   job. Confirm the liveness checker correctly gates the deliberate
   majority-loss windows (no false stall flag).
4. **Boundary honesty, both sides — run the complementary probes**:
   same quorum-pause schedule with (a) default 60 s window + 5 s delay
   and (b) 500 ms window + delay *below* it — both must stay GREEN.
   A red only past the boundary is what makes this a calibration, not
   a scare story.
5. **The observing RetryPolicy wrapper**: delegates decisions
   unchanged (no added/removed attempts), counts all internal
   attempts, thread-safe; confirm by comparing a run's wrapper counts
   against client debug logs or an independent count.
6. **Diffs**: SUT (`ADD`, expiry flag) minimal and itemized; workflow
   change is the scenario token + sustained-stream defaults claim
   (bare `counter-crash` cannot legally zero-retry — verify the
   arithmetic).

## Probe (≥1)

Beyond emphasis 4: kill the leader *during* a quorum-pause window
(compound fault — does the checker/gating stay coherent?); or run the
Q14 schedule with retries disabled entirely (every timed-out add
`:info` — checker must stay green via the 0-or-1 bound).

Deliver `reviews/09-counter-workload/09_report.md`; verdict PR
`Review 09: <verdict>`; self-merge if report-only.
