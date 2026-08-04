# Job 02 — Revision 1

*Coordinator relay, 2026-08-04, from Review 02 (verdict REVISE —
`reviews/02-env/02_report.md`, one required revision). Continue on your
existing branch/PR #3; per `jobs/README.md`, address the item below,
append a "Revision 1" section to `jobs/02-env/02_report.md` (do not
rewrite the original), and note completion in the PR. The same reviewer
will re-review the delta.*

## Required revision (1 of 1)

**`env/validate.sh` — check (b) must assert *current* leadership, not
election history.** Today it requires exactly one `changes role from
.* to LEADER` line across all logs; a legitimate early re-election
(the reviewer reproduced one with a 4 s leader pause) yields two lines
and fails a healthy cluster. Change the semantics to:

1. Per node, look at the **last** role-transition line only
   (`grep 'changes role from' ${LOG_FILE} | tail -n 1`) and count the
   node as leader iff that line matches `to LEADER`.
2. Keep the settle sleep, then **re-sample inside the existing
   `LEADER_DEADLINE` until the count is exactly 1** (a mid-handover
   sample may legitimately read 0 or 2); fail only at the deadline,
   dumping every node's last role-transition line as evidence.
3. Keep printing the winning node's LEADER line verbatim (the
   report-evidence requirement stands).
4. Update the now-stale "leader-uniqueness check can flake by design"
   Known-gaps entry via your report's Revision 1 section, and fix the
   check's description in `validate.sh`'s header comment and
   `env/README.md` wherever it states the old semantics.

Acceptance for the re-review: the reviewer's stall scenario (leader
paused ~4 s during settle, `RJ_LEADER_SETTLE=25`) passes — after
revision, a node whose last line is `LEADER to FOLLOWER` no longer
counts, the new leader's `... to LEADER` does, count = 1, green.

The review's Suggestions section is non-blocking — do not action it in
this revision.
