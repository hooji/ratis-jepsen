# Review 02 report — round 2 (revision 1 re-review)

Worker PR: #3, revised head `572557a` (delta reviewed: `e9ee12b..572557a`).
Re-reviewed in a fresh detached worktree at the revised head with a real
Docker daemon (x86_64), same sandbox setup as round 1
(`RJ_EXTRA_CA_BUNDLE=<single proxy root CA>`). Round-1 report:
`reviews/02-env/02_report.md` (verdict REVISE, one required revision).

## Verdict: MERGE

## Justification

The single required revision is implemented exactly as specified in
`jobs/02-env/02_revision_1.md`, and it passes the acceptance test that
revision named: replaying my round-1 stall scenario (leader paused 4 s
during a widened settle window) against the revised `validate.sh` now ends
in `ALL CHECKS PASSED`, on a run where I verified the poisonous state —
two `to LEADER` lines cluster-wide — actually existed. The delta touches
nothing outside the three files the revision called for, the quiet-path
behavior is unchanged (green baseline run), teardown spot-checks still
pass, and the job report carries a proper Revision 1 addendum. No blocking
findings remain.

## What I verified

**Delta scope.** `git diff e9ee12b..572557a` touches exactly
`env/validate.sh` (+31/−11), `env/README.md` (one usage line), and
`jobs/02-env/02_report.md` (Revision 1 section appended; original text
untouched). License header block in `validate.sh` intact. Ownership
respected.

**Revision item, sub-point by sub-point** (against
`jobs/02-env/02_revision_1.md`):

1. *Last-transition census*: new `last_transition()` helper
   (`grep 'changes role from' ${LOG_FILE} | tail -n 1`); `count_leaders()`
   counts a node iff that last line contains `to LEADER`. A stepped-down
   leader's later `LEADER to FOLLOWER` line removes it. ✓ (The substring
   match is safe against the real 3.2.2 formats — step-down lines read
   `LEADER to FOLLOWER` and never contain `to LEADER`.)
2. *Re-sample within the existing deadline*: after the kept settle sleep,
   the census re-samples once per second until it reads exactly 1, bounded
   by the same `LEADER_DEADLINE`; only the deadline fails, and the failure
   path dumps every node's last role-transition line. ✓
3. *Verbatim evidence line kept*: the winner's current `to LEADER` line is
   printed (`grep pattern | tail -n 1`). ✓
4. *Docs updated*: `validate.sh` header comment and `env/README.md` now
   state current-leadership semantics; the stale "can flake by design"
   Known-gaps entry is superseded via the report's Revision 1 section
   (original preserved, per `jobs/README.md`). ✓

**Quiet baseline (spot-check of previously-verified criteria).** Fresh
image build from the revised worktree, `up` → all 7 ssh-ready, then
`validate.sh` → exit 0 with the new wording; checks (a), (c), (d)
unchanged and green:

```
validate: PASS (a): startup line present on all five nodes
validate:   [n1] ... changes role from CANDIDATE to LEADER at term 1 for changeToLeader
validate: PASS (b): exactly one node (n1) is currently LEADER
validate: PASS (c): port 6000 listening on all five nodes
validate: PASS (d): all five servers stopped cleanly on SIGTERM
validate: ALL CHECKS PASSED          exit 0
```

**The acceptance test — round-1 stall scenario replayed.** Same procedure
as round 1 (`RJ_LEADER_SETTLE=25`; after check (b) began, paused the
elected leader's JVM 4 s via SIGSTOP/SIGCONT):

- Round 1 result (old check): `FAIL: (b) expected exactly one leader,
  found 2: n1 n2` — exit 1 on a healthy cluster.
- Round 2 result (revised check): n1 elected at term 1, stalled; n3 won
  term 2; `validate: PASS (b): exactly one node (n3) is currently LEADER`
  → `ALL CHECKS PASSED`, **exit 0**.

That this run genuinely exercised the fix (not a lucky single-election
boot) is confirmed from the logs: exactly two `to LEADER` lines existed
cluster-wide —

```
[n1] changes role from CANDIDATE to LEADER at term 1 for changeToLeader
[n3] changes role from CANDIDATE to LEADER at term 2 for changeToLeader
```

— the precise state the old census counted as 2, and n1's last transition
is `LEADER to FOLLOWER at term 1 for StepDownReason:LOST_MAJORITY_HEARTBEATS`,
so the revised census correctly reads 1.

**Teardown spot-check.** `down` → 0 project containers; second `down`
exit 0. Worktree `git status` clean after all runs (probes touched only
gitignored state).

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking | `env/validate.sh:129-141` | The (b) deadline is armed before the settle sleep, so `RJ_LEADER_SETTLE` values approaching `RJ_LEADER_DEADLINE` leave the exactly-one re-sample loop little or no budget (a single sample at deadline). Irrelevant at the defaults (5 s settle / 90 s deadline) and only reachable by deliberate knob choices; worth a one-line derive-after-settle if the knobs ever get real CI use. |
| 2 | non-blocking (observation) | `jobs/02-env/02_report.md` Revision 1 | The worker's note that `kill -0` succeeds on a SIGSTOPped process (so a stall overlapping check (d) would pause the stop-wait) is correct and worth keeping in mind for Job 04's nemesis work; not reachable in the committed flow. |

## Required revisions

None.

## Suggestions (non-blocking)

The round-1 suggestions (multi-cert `EXTRA_CA_B64` split, bundle size
pre-flight, `trap`-based failure summary, README note on the `maven-repo`
volume lifecycle) remain open and unactioned by design — the revision
relay scoped them out. They are recorded in `reviews/02-env/02_report.md`
and the coordinator's backlog.
