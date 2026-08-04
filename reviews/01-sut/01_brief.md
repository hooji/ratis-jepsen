# Review 01 — `sut/ratis-kv` (worker PR: `Job 01: sut/ratis-kv — the Ratis KV system under test`)

*Coordinator brief, 2026-08-04.*

**Read `reviews/README.md` first — it is binding** (independent
verification, worktree mechanics, verdict contract). The standard you
review against is `jobs/01-sut/01_brief.md`, especially its numbered
Acceptance criteria. This file only adds emphasis.

## Baseline (always)

Re-run the full verification yourself in the worktree
(`./mvnw -q -f sut/ratis-kv/pom.xml verify`, then `package` + untar +
`bin/ratis-kv --help`), confirm file ownership, license headers, no
committed artifacts, and that the report's claimed outputs reproduce.

## Emphasis — where to dig on this particular PR

1. **Config-profile truth check (highest value).** `Main` wires the
   production profile through typed setters
   (`RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled`,
   `.Log.setPurgeUptoSnapshotIndex`, `.Rpc.setTimeoutMin/Max`,
   `.Read.setOption`, `GrpcConfigKeys.Server.setPort`). The job brief's
   authoritative artifacts are the config **key strings**. Fetch the
   Ratis `ratis-3.2.2` sources for those ConfigKeys classes and confirm
   each setter writes exactly the intended key — a wrong-but-compiling
   setter is the silent failure mode that would invalidate every later
   run. Quote the key constants you confirmed in your report.
2. **Does anything actually load a snapshot?** The smoke test proves a
   snapshot file appears and that state survives `RECOVER` — but if the
   Raft log is still intact, restart may recover purely by log replay
   and the snapshot-*load* path (`initialize`/`reinitialize` reading the
   file back into the map) could be dead code. Verify it isn't: inspect
   the load implementation, then force the question in the worktree —
   e.g. rerun the restart scenario after deleting/moving the log
   segments under one server's storage dir (keeping `sm`/snapshot), or
   demonstrate via added logging that the snapshot file was read on
   restart. If load is broken or unexercised, that is **blocking**.
3. **Seed-bug isolation.** (a) Flag off ⇒ machinery inert: no scheduler
   thread created, no shadow writes, zero behavioral difference. (b)
   Flag on ⇒ *writes remain correct* — the bug must corrupt reads only
   (CAS decisions must come from the primary map). (c) The negative arm
   of `StaleReadsSeedBugTest` ("never stale with flag off") — assess
   flakiness risk: is it timing-dependent in a way that could pass or
   fail spuriously on a loaded machine?
4. **Snapshot/apply concurrency.** `takeSnapshot` copies under
   `applyLock` that `applyTransaction` also takes; the shadow applier is
   a scheduled executor. Confirm: single-threaded shadow executor (FIFO
   apply order), no lock interplay that could stall the
   StateMachineUpdater thread, and deterministic snapshot content
   (TreeMap claim).
5. **Test-client retry masking.** `MiniCluster.sendUntilSuccess(...,
   60s)` exists for first-leader election. Confirm it is used only where
   retry-until-leader is legitimate (first contact), not in a way that
   could mask genuine op failures in the assertions.
6. **Launcher/tarball behavior beyond `--help`.** From the untarred
   layout, start `bin/ratis-kv` with a deliberately broken setup — an
   already-occupied port, or an unwritable `--storage` dir — and confirm
   the loud non-zero exit the report claims; confirm `bin/ratis-kv`
   works cwd-independently (classpath resolved relative to the script,
   `exec java`).

## Probe suggestions (pick at least one — reviews/README rule 3)

- Kill the leader's `RaftServer` mid-writes in an in-JVM variant and
  confirm the cluster keeps accepting writes after failover.
- Start two processes on the same storage dir — expect a loud,
  comprehensible failure (Ratis storage lock), not corruption.
- Feed the codec adversarial inputs the unit tests didn't cover
  (very long keys, `-` values, leading/trailing spaces, unicode).

## Out of scope for this review

Docker/env concerns, harness/Clojure concerns, membership (`--join`),
performance. Improvements in those directions are Suggestions, not
blockers.

Deliver `reviews/01-sut/01_report.md` per `reviews/README.md`, verdict
**MERGE** or **REVISE**, via a PR titled `Review 01: <verdict>`.
