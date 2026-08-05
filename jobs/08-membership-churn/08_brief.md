# Job 08 — M2 part 2: membership churn (the L3-orchestrator rehearsal)

*Coordinator brief, 2026-08-05.*

**Read `jobs/README.md` first — binding.** Then `docs/PLAN.md` (Q5,
M2), `docs/DESIGN.md` (§1.2 note: "the launcher grows a `--join` mode
then" — *then* is now; §2.6 contract), and this brief. Base on current
`main` (Job 07 merged: snapshot churn, transfer, follower reads,
evidence-assertion law).

## Context

The last M2 piece: dynamic membership against the dormant pool
(`n6`/`n7` have existed since Job 02 for exactly this). This nemesis
doubles as the rehearsal of the future StateStore L3 orchestrator's
add-replace-dead-node flow, and it drives the membership defect class
(RATIS-1912 election storms, RATIS-2274 stale-conf) plus — combined
with snapshot churn — the bootstrap-catch-up-via-install-snapshot path.
**This job may modify `sut/**`** (the anticipated `--join` launcher
mode); it is the first job since 01 allowed to, so keep that diff
minimal and separately described in your report.

## Deliverables

1. **SUT `--join` mode** (`sut/ratis-kv`): start a server that is NOT
   in the initial conf and awaits being added — the Ratis flow (verify
   details in the 3.2.2 sources; Ratis's own `GroupManagementBaseTest`
   is the precedent): start the `RaftServer` without a formed group,
   then the *harness* bootstraps it via
   `client.getGroupManagementApi(peerId).add(group)` before the leader
   commits it into the conf. Extend the startup-line contract
   compatibly (same line, or a documented `--join` variant — DESIGN
   §2.6 gets a coordinator edit on merge; propose the wording in your
   report). Unit/smoke coverage for the new path in the existing SUT
   test style.
2. **Membership-churn nemesis** (`--nemesis membership`): randomized
   cycle over — **add** (pool node → voter, up to 7), **remove**
   (voter → pool, down to 5... floor never below 3, target band 5±2),
   **replace-dead** (kill a voter; remove it from conf; add a pool
   node; wipe+restart the killed one as pool — the L3 rehearsal
   sequence). Mechanics: `AdminApi.setConfiguration` with
   `SetConfigurationRequest.Arguments` — **use the Arguments builder
   or List overloads; the `(RaftPeer[], RaftPeer[])` overload is
   broken at 3.2.2** (RATIS-2640, our own upstream find; fixed on
   master, not in 3.2.2). Prefer `Mode.COMPARE_AND_SET` for
   race-safety, tolerating mismatch-retry;
   `ReconfigurationInProgressException` and transfer-window rejections
   are legal outcomes, not nemesis crashes.
3. **Evidence assertion** (the law): a dedicated membership run must
   prove ≥ N committed configuration changes happened (server logs
   and/or `GroupInfo` queries — quote the observed phrasing); a
   replace-dead composed with snapshot churn must additionally show
   install-snapshot evidence on the joining node. Zero-evidence runs
   fail with a distinct error.
4. **Listener-staging probe** (bounded; report-and-defer allowed):
   one scripted sequence — add `n7` as LISTENER
   (`setListenersInNewConf` via the builder), verify it replicates
   (log census), promote it to voter via a conf that moves it to the
   servers list, then demote and remove. Our evaluation flagged
   listener↔follower conversion as an open upstream question
   (RATIS-1825 open 40+ months vs. code that appears to support it) —
   whatever you observe is signal: clean pass, or a reproducible wedge
   (preserve everything; a wedge here corroborates a known upstream
   bug and outranks the probe's completion).
5. **Runs + ledger**: membership ×2 green; membership+snapshot-churn
   combined green (with both evidence kinds); `mixed-all` (now
   including membership) green; seeded-red under membership convicts.
   Liveness-checker gating: assess whether membership windows need
   nemesis-aware gating (staged catch-up should not stall a healthy
   majority — if you must gate, justify narrowly).
6. **CI scenarios default line** updated (same single-line grant as
   Job 07).
7. **`jobs/08-membership-churn/08_report.md`** per `jobs/README.md`.

## File ownership

`sut/**` (minimal `--join` diff), `harness/**`, `docs/RUNS.md`
(append), `.github/workflows/jepsen.yml` (scenarios-default line),
`jobs/08-membership-churn/08_report.md`. **Parallel-safe with: none.**

## Acceptance criteria (command + output excerpt each)

1. SUT suite green incl. new `--join` coverage
   (`sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml verify`); harness
   `clojure -M:test` green, no regressions.
2. Membership ×2 green with conf-change evidence counts quoted.
3. Combined membership+snapshot-churn green with install-snapshot
   evidence on a joining node quoted.
4. Listener-staging probe outcome documented (pass, or preserved
   wedge + triage).
5. `mixed-all` green; seeded-red under membership convicts.
6. Established reporting: analysis times, `:info` sanity, headers,
   ownership (SUT diff minimal and itemized), report.

## Non-goals

Increment workload (M3), lazyfs (M4), version matrix (M5), any
`env/**` change, orchestrator-grade conf reconciliation (track
intended conf simply; this is a nemesis, not a control plane).

## Note

Same live-territory rule as Job 07, doubled: membership + snapshot
churn is where the evaluation's correctness archaeology says real
Ratis bugs cluster. A reproducible conviction or wedge, preserved and
triaged, outranks a completed matrix.
