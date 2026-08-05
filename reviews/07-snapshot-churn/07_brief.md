# Review 07 — snapshot churn, transfer, follower reads (worker PR #15)

*Coordinator brief, 2026-08-05.* **Read `reviews/README.md` first.**
Standard: `jobs/07-snapshot-churn/07_brief.md` + the worker's
documented deviations (which look legitimate and well-evidenced — but
verifying them is this review). Requires Docker.

## Emphasis

1. **`LeaderSteppingDownException` → `:fail` — treat as potentially
   BLOCKING until proven, exactly like the NLE episode.** The worker
   classifies write-path LSDE as definite-not-applied (pre-append
   admission rejection). That is the same *shape* of claim whose NLE
   variant produced the Review 05 false-red. Settle it from the
   ratis-3.2.2 sources: enumerate every code path that raises
   LeaderSteppingDownException toward a client write, and prove none
   of them can fire for an entry that was already appended (contrast
   with the deposed-leader pending-entry completion path that made NLE
   ambiguous). If any appended-entry path exists, the row must be
   `:info` and the verdict REVISE. Quote the settling lines either
   way. Then probe empirically: transfer-heavy schedule, check no
   convictions and no `:info` flood.
2. **The churn-mechanism deviation.** Re-verify from source the three
   claims the redesign rests on: purge releases only *closed*
   segments; the open segment closes at the size threshold or on term
   change; `purge.gap` floors purge frequency. Then confirm the
   shipped cycle actually produces install-snapshot on your runs
   (evidence counts > 0) and that the negative arm works (defanged
   churn → `:valid? false`, distinct error).
3. **Re-run the matrix**: snapshot-churn ×2, transfer,
   `--reads mixed` under partition, `mixed-all`, seeded-red under
   snapshot-churn. Analysis times; `:info` sanity (specifically: the
   pre-fix OOM schedule re-run clean on the new row — the preserved
   OOM store should have its recurrence disproven).
4. **The no-backoff InstallSnapshot observation**: reproduce it
   (~hundreds of `ServerNotReadyException`-answered attempts during
   follower reboot), quantify on your run, and assess severity
   honestly (converges cleanly at n=5 — but is it RATIS-2500-adjacent
   behavior worth an upstream report with a repro?). Your assessment
   feeds the coordinator backlog either way.
5. **Transfer-target selection**: verify the log-census-based
   exclusion of the sitting leader is correct and can't go stale
   (transfer-to-self silently defeats the term-bump purpose — how does
   the code know who leads *now*?), and that
   `TransferLeadershipException` timeouts are tolerated as legal.
6. **`--rate` and budgets**: op caps still enforced; runs bounded;
   knossos inputs not inflated past the analysis budget.
7. **Workflow diff**: exactly the one scenarios-default line.

## Probe (≥1)

Kill the *leader* (not a follower) mid-snapshot-churn cycle; or run
churn with an adversarially tiny `--rate` (does the evidence checker
correctly fail the run as tested-nothing rather than passing?).

Deliver `reviews/07-snapshot-churn/07_report.md`; verdict PR
`Review 07: <verdict>`; self-merge if report-only.
