# Job 05 — Revision 1

*Coordinator relay, 2026-08-05, from Review 05 (verdict REVISE —
`reviews/05-nemesis-breadth/05_report.md`). Continue on your existing
branch/PR #10; append a "Revision 1" section to your report; the same
reviewer re-reviews the delta.*

Execute the reviewer's **Required revisions 1–5 exactly as written in
their report** — they are worker-executable as specified (bounded
same-callId retry policy; NLE/LNR/null-cause-RRFE write verdicts →
`:info` residual; outcome tests; `mixed` ×3 + full gate re-runs;
RUNS.md + report updates). Two coordinator notes on top:

1. **The DESIGN amendment is ratified** — as of this relay, DESIGN
   §2.4 and PLAN Q3 already reflect the new policy (bounded
   same-callId retries; residual-ambiguity rows), so your change
   implements current design, not a deviation. Still record the
   history in your report's Deviations section as the reviewer asked.
2. Context for your docstring citations: the soundness argument
   (callId captured once in `BlockingImpl.send`, server retry cache
   dedups re-attempts, deposed-leader-committed writes return the
   cached success) is the reviewer's, verified against ratis-client
   3.2.2 — cite their report rather than re-deriving.
