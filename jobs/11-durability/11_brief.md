# Job 11 — M4: durability faults via lazyfs

*Coordinator brief, 2026-08-06.*

**Read `jobs/README.md` first — binding.** Then `docs/PLAN.md` (M4),
`jobs/10-fuse-spike/10_report.md` + its review (the spike this builds
on), and `docs/BACKLOG.md` item 4. Base on current `main`.

## Context

The spike settled it: lazyfs works in our containers and on hosted
runners; and single-node clear-cache faults cannot remove acknowledged
writes (Ratis syncs each append before acking), so this milestone's
leverage is **torn writes** and **quorum-wide loss of un-synced
data** — the fault class behind JRaft's TLA+-found ordering bugs, and
the one `kill`-free... the one process-stop-based testing can never
reach. BACKLOG item 4's standing note applies: out-of-model faults
need *deliberately stated expectations*, not inherited crash-model
assumptions.

## Deliverables

1. **Image + mount plumbing**: lazyfs (spike's pinned commit) baked
   into `env/Dockerfile` with the spike's accommodations (dependency
   pre-fetch; address the ~8 s / 1 GiB per-mount pre-allocation —
   smaller cache or measured acceptance, documented). `db.clj` gains a
   per-node lazyfs mount lifecycle: when a durability scenario asks,
   the node's `--storage` dir is backed by a lazyfs mount created
   before SUT start and reliably unmounted on wipe/teardown (st