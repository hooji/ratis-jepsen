# Job 07 — M2 part 1: snapshot churn, leadership transfer, follower reads

*Coordinator brief, 2026-08-05.*

**Read `jobs/README.md` first — binding.** Then `docs/PLAN.md` (M2),
`docs/DESIGN.md`, `docs/BACKLOG.md` item 5, and this brief. Base on
current `main` (M1 complete: five scenarios, liveness checker, CI).

## Context

M0/M1 proved the harness on generic faults. M2 aims it at **Ratis's
actual defect history**: install-snapshot is the project's
highest-defect-density subsystem (RATIS-2500 infinite notification
loop, RATIS-2147/-2166 digest/index bugs, RATIS-2523 stuck-appender
after restart), and follower linearizable reads are a
differentiating claim we've never tested under faults. This job forces
those paths **without membership changes** (Job 08's territory).
Everything here is reachable with zero SUT changes: client-triggered
snapshots + `purge.upto.snapshot.index=true` (already the SUT profile)
+ a held-back follower ⇒ recovery *must* go through install-snapshot.

## Deliverables

1. **Snapshot-churn nemesis** (`--nemesis snapshot-churn`): cycle of —
   kill one follower; drive writes; trigger snapshots via the client
   admin interop (`SnapshotManagementApi` — `client.getSnapshotManagementApi(...)
   .create(timeoutMs)`, per-server as the API requires) so the log
   purges past the dead follower; restart it (install-snapshot is now
   its only recovery path); heal, next cycle. Configurable cadence;
   compose-able into `mixed-all`.
2. **Install-snapshot evidence assertion — the test must prove it
   tested something** (the Review 01 lesson): after a snapshot-churn
   run, the harness verifies from node logs (via `jepsen.control`
   grep) that install-snapshot actually occurred (leader-side send
   and/or follower-side receive lines — find the exact ratis-3.2.2 log
   phrasings by observation, quote them), and **fails the run**
   (`:valid? false`, distinct `:error`) if the count is zero — a
   snapshot-churn run that never exercised install-snapshot is a
   broken test, not a green one.
3. **Leadership-transfer nemesis** (`--nemesis transfer`): periodic
   `AdminApi.transferLeadership(peer, timeoutMs)` to a random voter
   via client interop; tolerate `TransferLeadershipException` on
   timeouts (that's a legal outcome, not a nemesis crash).
4. **Follower-read workload option** (`--reads leader|follower|mixed`,
   default `leader` preserving current behavior): follower/mixed sends
   reads via `sendReadOnly(msg, followerPeerId)` to a non-leader —
   the linearizable follower-read path. Checker unchanged: those reads
   claim full linearizability and are judged accordingly.
5. **Runs + ledger** (`docs/RUNS.md` append): snapshot-churn ×2 green
   with install-snapshot evidence counts quoted; transfer green;
   follower-reads (`--reads mixed`) under `partition` green;
   seeded-red under snapshot-churn (must still convict); one
   `mixed-all` (all nemeses incl. new ones) green. Outcome-mapping
   sanity on the snapshot-churn run.
6. **CI defaults**: update the `scenarios` default list in
   `.github/workflows/jepsen.yml` to include the now-existing
   scenarios (that single line is granted to you; nothing else in the
   workflow).
7. **`jobs/07-snapshot-churn/07_report.md`** per `jobs/README.md`.

## File ownership

`harness/**`, `docs/RUNS.md` (append), `.github/workflows/jepsen.yml`
(the scenarios-default line only), `jobs/07-snapshot-churn/07_report.md`.
No `sut/**`, no other `env/`/workflow changes. **Parallel-safe with:
none** (Job 08 follows sequentially in `harness/**`).

## Acceptance criteria (command + output excerpt each)

1. `clojure -M:test` green (new units for nemesis cycle logic and the
   evidence assertion — fabricated log fixtures for zero/nonzero
   cases; no regressions).
2. Snapshot-churn ×2: `:valid? true`, install-snapshot evidence counts
   > 0 quoted verbatim from node logs.
3. Evidence-assertion negative proof: demonstrate (unit fixture or a
   deliberately defanged run) that a churn run with zero
   install-snapshot events goes `:valid? false` with the distinct
   error.
4. Transfer run green; follower-reads-under-partition run green;
   `mixed-all` green; seeded-red under snapshot-churn convicts.
5. Analysis-time and `:info`-sanity reporting as established.
6. Headers, ownership (workflow diff = one line), report.

## Non-goals

Membership/pool changes (Job 08), SUT modifications, increment
workload (M3), lazyfs (M4), version matrix (M5).

## Note — you are now hunting in live territory

This job aims at the subsystem where real Ratis bugs historically
live. If a green-config run convicts, or install-snapshot wedges
(RATIS-2500-style loops — watch for repeated notification lines), the
established discipline applies with extra weight: preserve the store
and logs, triage harness-vs-SUT-vs-Ratis, and report loudly — a
reproducible Ratis finding here outranks every other deliverable in
this brief. Snapshot success is asserted from disk/log state, never
from the `SnapshotManagementApi` reply (BACKLOG item 5).
