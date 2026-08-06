# Review 08 — membership churn + the pause() conviction (worker PR #17)

*Coordinator brief, 2026-08-05.* **Read `reviews/README.md` first.**
Standard: `jobs/08-membership-churn/08_brief.md` + the worker's
deviations. Requires Docker. This PR carries the project's two
strongest upstream-candidate claims — verifying them *is* the review.

## Emphasis

1. **The `pause()` conviction chain, end to end from source.** Verify
   each link at ratis-3.2.2: `BaseStateMachine.pause()` is a no-op
   that never moves the lifecycle to PAUSED; the streamed-install path
   calls `pause()` then `StateMachineUpdater.reload()` asserts the
   PAUSED state; the assertion failure's handler closes the division.
   Then verify the SUT fix mirrors upstream's own test state machine's
   lifecycle discipline, and empirically: a fixed joiner survives
   **repeated** live installs (division stays open, applies continue).
2. **The retroactive reconciliation — mandatory.** Two prior results
   must be re-explained coherently against this find and each other:
   (a) Job 07's snapshot-churn runs were **green with install
   evidence** on the *broken* `pause()` — establish precisely why
   (e.g. install completing on disk before the division died +
   RECOVER restart next cycle masking the kill; or evidence counted
   leader-side sends) and state plainly whether Job 07's green runs
   were measuring what we thought; (b) the Job 07/Review 07 "storm
   converges cleanly" reading vs. this job's
   "installs into a freshly-killed division" — which is the truth, and
   does BACKLOG item 7's severity/framing need rewriting? Your answer
   updates the historical record; the coordinator will amend RUNS/
   BACKLOG per your findings.
3. **`GroupInfoReply.getConf()` wire drop.** Verify from the 3.2.2
   serializer (`toGroupInfoReplyProto`) that the conf field is never
   set; confirm the log-census fallback's soundness — especially the
   transitional-only (`old=peers:`) counting rule and its rationale
   (new leaders re-append the current conf as a stable entry, which
   would inflate naive counts). Probe the census against a run with
   elections-but-no-membership-changes: count must be zero.
4. **`--join` mode.** Null-group start + `GroupManagementApi.add`
   with the empty-peers group + `setConfiguration`; restart semantics
   (proxy recovers stored groups by storage-dir scan — `--join` is
   the correct restart mode for joined nodes). Verify against the
   `GroupManagementBaseTest` precedent; run `JoinModeTest` and the
   SUT suite; audit the `sut/**` diff for minimality (itemized in the
   report).
5. **Nemesis orchestration.** COMPARE_AND_SET against the census;
   voter floor ≥ 3 enforced (probe: can a hostile schedule drive it
   below?); `ReconfigurationInProgressException`/transfer-window
   rejections tolerated as legal; replace-dead sequence matches the
   brief (incl. wipe-and-restart-as-pool).
6. **The listener-staging probe outcome** — whatever the report says,
   reproduce it once and grade the evidence (this feeds the
   RATIS-1825 question; a wedge must be preserved, a pass must be
   real).
7. **The matrix**: membership ×2, combined membership+snapshot-churn
   (both evidence kinds), `mixed-all`, seeded-red under membership —
   all on the fixed SUT; established reporting (analysis times,
   `:info` sanity, workflow diff = one line).

## Probe (≥1)

Re-run a Job 07-style snapshot-churn schedule on the **fixed** SUT and
compare install/kill behavior against the preserved Job 07 stores (this
directly serves emphasis 2); or drive membership churn with
`--time-limit 120` twice for stability.

Deliver `reviews/08-membership-churn/08_report.md`; verdict PR
`Review 08: <verdict>`; self-merge if report-only.
