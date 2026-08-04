# Review 02 — `env/` (worker PR: `Job 02: env/ — containerized cluster topology and entry point`)

*Coordinator brief, 2026-08-04.*

**Read `reviews/README.md` first — it is binding.** The standard is
`jobs/02-env/02_brief.md` (numbered Acceptance criteria) plus the frozen
deployment contract in `docs/DESIGN.md` §2.6. This file adds emphasis.
Note the worker reused the branch name from merged Job 01 — the PR diff
against `main` is what you review.

## Baseline (always)

Reproduce every acceptance criterion yourself in the worktree: full
`env/run.sh up` → `env/validate.sh` → `env/run.sh down` → `down` again
(idempotence) → `up` again (no leakage). This review requires Docker in
your environment; if you don't have it, stop and say so in your report
rather than reviewing by inspection alone. Verify file ownership
(`env/**`, the one `.gitignore` block, the report), headers, no
committed secrets or state.

## Emphasis

1. **SSH/key hygiene.** `run.sh` generates a keypair under
   `env/.state/` (gitignored). Confirm: nothing key-like is committed
   anywhere in the PR; regenerated state after `down`/`up` cycles keeps
   working; `env/ssh_config` relaxations (host-key checking etc.) are
   scoped to the compose network's hosts only, not a global pattern a
   user's own ssh could pick up.
2. **Leader-check robustness.** `validate.sh` asserts "exactly one
   node's log shows `changes role from .* to LEADER`" within a
   deadline. Two questions: (a) does that pattern match the real Ratis
   3.2.2 log line (quote the observed line verbatim); (b) is "exactly
   one" flaky under legitimate early election churn (a re-election
   inside the window would show two)? Run `validate.sh` at least twice;
   if you can, once under CPU load. If the check can spuriously fail on
   a healthy cluster, that is a REVISE-grade robustness finding — this
   script becomes CI's first gate in M1.
3. **Teardown safety.** Confirm `down` targets only this project's
   containers/network/volumes (label or name-prefix scoped) — it must
   be impossible for it to remove unrelated Docker resources on a dev
   machine. Then confirm true idempotence and clean `docker ps` after.
4. **Multi-arch honesty.** You'll run on one architecture. Inspect the
   Dockerfile for arch-pinned artifact URLs or binaries; verify the
   JDK/Clojure install mechanism genuinely selects per-arch; state
   plainly in your report which arch you tested and that the other is
   inspection-verified only.
5. **Contract fidelity.** Check every path/port/user in the scripts
   against DESIGN §2.6 literally (`/opt/ratis-kv`, `/var/lib/ratis-kv`,
   `/var/log/ratis-kv.log`, port 6000, root, `n1..n7`); Job 03's
   `db.clj` was written against that table sight-unseen, so any drift
   here breaks Job 04.
6. **The `test` stub.** Exit code 64, clearly marked for Job 04
   replacement, and `run.sh`'s structure makes that replacement local.

## Probe suggestions (pick at least one)

- `docker kill n4` mid-`validate.sh` — does the failure name the node
  and exit non-zero comprehensibly (not hang past deadlines)?
- Delete `env/.state/` while the cluster is up, then `down`/`up` —
  recovery or loud failure, never silent wedge.
- Run `validate.sh` from a different working directory (script
  cwd-independence).

## Out of scope

Harness/Clojure concerns (Job 03's review), workloads, CI, lazyfs,
`n6`/`n7` serving traffic, image-size golf.

Deliver `reviews/02-env/02_report.md` per `reviews/README.md`; verdict
PR titled `Review 02: <verdict>`; self-merge it if report-only.
