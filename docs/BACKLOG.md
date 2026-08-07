# Backlog — accepted non-blocking findings and future-milestone notes

*Items carried forward from reviews; each names its source. The
coordinator turns these into jobs when their milestone arrives.*

*Housekeeping pass 2026-08-07 (Job 13): items are never deleted. Ones
whose milestone has since arrived and been satisfied are marked
**[CLOSED]** with what closed them; everything unmarked is still open.
Items 7–11 are the findings about Ratis and are quoted elsewhere — their
classifications (candidate / already-fixed-upstream / open question) are
binding on every document in this repository.*

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
4. **[CLOSED — Job 11 defined them] M4 design note (committed-state loss):** Review 01's log-ablation
   probe showed that deleting durable state *unequally* across nodes
   (an out-of-model fault — loss, not crash) produced a 60 s+ read
   outage. M4's lazyfs scenarios must define expectations for
   committed-state loss deliberately, not inherit crash-model
   assumptions.
5. **[CLOSED as a design note — the Ratis-side observation stays open]
   M2 design note:** snapshot success must be asserted from disk
   state, never from the `SnapshotManagementApi` reply (Review 01
   observed `success=true` while `takeSnapshot` threw). Ratis-side
   behavior potentially worth an upstream report once we have a clean
   repro. *(Job 07 built exactly this: the install-snapshot evidence
   checker convicts from node-log events, and its first gate failed a
   run whose snapshot replies were all `:success? true` but which
   produced zero installs. No clean repro of the misreporting itself
   has been captured, so nothing is filed.)*
6. **[CLOSED — the workflow uses the module path] CI invocation note (M1):** build commands address the module
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
   `reviews/08-membership-churn/08_report.md`. **Still present at the
   3.3.0 RC2 artifacts** (Job 12 / Review 12: in-JVM library probe with
   a naive `BaseStateMachine` subclass; 3.2.2 control arm reproduces
   the Job 08 conviction, RC2 arm behaves identically).
8. **FIXED UPSTREAM (regression-test material, not a defect report):
   `GroupInfoReply.getConf()` dropped by the wire serializer** at
   3.2.2 (`toGroupInfoReplyProto` never sets the field) — Job 08,
   verified by Review 08. **Job 12 / Review 12 (2026-08-06) confirmed
   it is populated at the 3.3.0 RC2 artifacts** (one-line source
   change located and quoted; verified over real gRPC). Our log-census
   workaround remains functional and is still required for 3.2.2, with
   no version branch needed. Upstream framing: not a defect to report
   — offer the test that keeps it fixed.
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
   engagement. **Still present at the 3.3.0 RC2 artifacts** (Job 12 /
   Review 12: all four conf transitions commit, reads still refused in
   STARTING, mechanism source-identical).
10. **Upstream question (not yet a candidate): no parent-directory
    sync after the raft-meta rename.** Job 11 / Review 11
    (2026-08-06). Established: `term`/`votedFor` are written and
    synced before the node acts on a vote (source-proven at 3.2.2,
    probe-consistent) — there is **no double-vote defect at 3.2.2**.
    The open question is narrower: `FileUtils.move` and its callers
    perform no directory sync after the rename, and POSIX does not
    make a rename durable without one (etcd/ZooKeeper/LevelDB sync the
    parent for exactly this). Worst case is recovering the *previous*
    raft-meta with an older term/vote — the re-vote hazard. Review 11
    narrowed it usefully: an empty/unparseable meta file fails safe
    (startup refusal via `getTerm → orElseThrow`), so only the
    old-but-parseable-content case matters. **This harness cannot
    demonstrate it** (renames pass through lazyfs); a
    dm-flakey/CrashMonkey-style follow-up could. Frame any external
    mention as a question with a mechanism, never as a found defect.
11. **Probe-rule hardening before external quotation** (Review 11
    finding 1): `harness/scripts/metadata-probe.sh` decision rules
    skip samples whose backing term is unparseable, so a planted
    sync-lying SUT still printed PASS. The dangerous
    parseable-old-term shape *is* detected and the absence shape fails
    safe, so the conclusion stands — but harden both rules (count
    mount-parseable + backing-unparseable as divergence; treat an
    unparseable recovered term or failed victim restart as a finding)
    before quoting the experiment externally. Until then the correct
    phrasing is "source-proven; probe-consistent".
12. **lazyfs defects — FILED 2026-08-06** (dsrhaslab/lazyfs
    [#15](https://github.com/dsrhaslab/lazyfs/issues/15): fifo
    `torn-op` silently dropped when `occurrence=` is supplied;
    [#16](https://github.com/dsrhaslab/lazyfs/issues/16): dropped fifo
    commands still log `configured successfully`). Characterized in
    Job 11's report; we offered to test a patch. No action pending on
    our side.
13. **Checker-cost variance / elle migration (Review 11 finding 4):**
    the same whole-cluster shape analyzed in 8.2 s on one host and
    17 s on another — the margin to the checker's practical limit
    varies ~2× by machine. The elle migration deferred at Job 05 would
    retire this tuning class entirely; revisit when a workload needs
    it.
14. **[CLOSED — all four landed in Job 06] Env hardening (Review 02
   round-1 suggestions, 2026-08-04, all
   non-blocking):** multi-cert `EXTRA_CA_B64` split; image/bundle size
   pre-flight check; `trap`-based failure summary in `validate.sh`;
   README note on the `maven-repo` volume lifecycle. Batch into an env
   polish job before or alongside M1 CI wiring.
15. **[CLOSED — Job 16 (2026-08-07) added both files] No `LICENSE` or
   `NOTICE` file at the repository root.** Raised as
   an out-of-scope suggestion by Job 06 (2026-08-05) and still open at
   2026-08-07. Every source file carries the Apache-2.0 header and
   `README.md` states the license, but PLAN Q17 called for an
   Apache-2.0 `LICENSE` from the first commit plus a `NOTICE` crediting
   the studied prior art — and the repository is public with an ASF
   donation as the stated endgame. Coordinator-level fix (no job owns
   the repository root); re-reported by Job 13, which is
   documentation-only and could not add it; re-reported again as
   required item 2 of the Job 15 capstone, and executed by Job 16
   (canonical Apache-2.0 `LICENSE`; `NOTICE` with project attribution,
   the sofa-jraft-jepsen shape-only prior-art credit, and the
   fetched-not-redistributed dependency note).
