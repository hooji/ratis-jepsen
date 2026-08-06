# Backlog — accepted non-blocking findings and future-milestone notes

*Items carried forward from reviews; each names its source. The
coordinator turns these into jobs when their milestone arrives.*

1. **Bound key length in `KvCodec` + snapshot write-to-temp-then-rename.**
   Review 01 finding 1 (2026-08-04): an over-64 KiB key passes the codec,
   commits, then permanently poisons `takeSnapshot` on every node
   (`DataOutputStream.writeUTF` limit), leaving partial snapshot files
   and blocking log purge — while `SnapshotManagementApi` still reports
   success. Unreachable by M0–M3 workloads (short fixed keys). **Must be
   fixed before any adversarial/fuzz workload exists.** Suggested fix:
   reject keys > 1 KiB as `Malformed`; write snapshots to a temp path and
   rename.
2. **`Reply.Err("")` encode/decode asymmetry** — Review 01 finding 2.
   One-line guard whenever `KvCodec` is next touched.
3. **Snapshot copy under `applyLock` blocks apply** — Review 01
   finding 3. Revisit at the RocksDB state-machine stage, not before.
4. **M4 design note (committed-state loss):** Review 01's log-ablation
   probe showed that deleting durable state *unequally* across nodes
   (an out-of-model fault — loss, not crash) produced a 60 s+ read
   outage. M4's lazyfs scenarios must define expectations for
   committed-state loss deliberately, not inherit crash-model
   assumptions.
5. **M2 design note:** snapshot success must be asserted from disk
   state, never from the `SnapshotManagementApi` reply (Review 01
   observed `success=true` while `takeSnapshot` threw). Ratis-side
   behavior potentially worth an upstream report once we have a clean
   repro.
6. **CI invocation note (M1):** build commands address the module
   explicitly (`sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml ...`) — no
   repo-root wrapper is provided; briefs/workflows must use the module
   path (Review 01 suggestion 3 resolved this way: no fragile root
   symlink).
7. **Upstream candidate #1 (strongest): `BaseStateMachine.pause()` is a
   no-op that kills divisions on live snapshot install.** Job 08 /
   Review 08 (2026-08-05/06), chain verified from 3.2.2 source and
   reproduced live: empty `pause()` never reaches PAUSED →
   `StateMachineUpdater.reload()`'s lifecycle assert throws → the
   catch-all closes the division, ~4 ms after the install renamed
   successfully. Any integrator leaning on the shipped base class
   inherits this. Secondary, still real: the leader hammers the dead
   division with **no backoff** (4,876 `ServerNotReadyException`
   traces / 130 send-snapshot re-initiations in one reviewed run —
   supersedes the original Job 07 framing of the "retry storm", whose
   green runs were measuring durable installs while missing the
   division deaths; see the RUNS correction note). Upstream action:
   one issue for the base-class/lifecycle trap (fix: default `pause()`
   honoring the contract, or reload tolerating it), one for install
   retry backoff. Stores + exact frames in
   `reviews/08-membership-churn/08_report.md`.
8. **Upstream candidate #2: `GroupInfoReply.getConf()` dropped by the
   wire serializer** at 3.2.2 (`toGroupInfoReplyProto` never sets the
   field) — Job 08, verified by Review 08. Harness works around via
   log-line conf census (transitional-entries-only counting).
9. **Upstream candidate #3: staged LISTENER never leaves STARTING —
   the RATIS-1825 corroboration.** Job 08's probe, reproduced by
   Review 08, both stores preserved: conf-level listener staging,
   promotion, demotion and removal all commit correctly, but a
   groupAdd'd listener division stays lifecycle STARTING (serves no
   client requests, ever) because `checkStaging`'s caught-up mark uses
   FOLLOWER-only `containsInConf(id)`, so its AppendEntries keep
   `initializing=true` (`RaftServerImpl:1611`). Pinned one-line
   suspect: the filter needs `containsInConf(id, FOLLOWER, LISTENER)`.
   This answers the evaluation's open RATIS-1825 question with a
   mechanism and a candidate fix — prime material for the upstream
   engagement.
10. **Env hardening (Review 02 round-1 suggestions, 2026-08-04, all
   non-blocking):** multi-cert `EXTRA_CA_B64` split; image/bundle size
   pre-flight check; `trap`-based failure summary in `validate.sh`;
   README note on the `maven-repo` volume lifecycle. Batch into an env
   polish job before or alongside M1 CI wiring.
