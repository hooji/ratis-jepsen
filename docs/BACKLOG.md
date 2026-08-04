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
