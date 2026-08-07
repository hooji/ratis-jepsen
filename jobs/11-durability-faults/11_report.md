# Job 11 report — M4: durability faults via lazyfs

## Summary

The harness can now break the storage layer's durability contract, not
just the process: `--nemesis unsync-drop | unsync-drop-all |
torn-write` run every node's `/var/lib/ratis-kv` on a lazyfs mount and
discard un-synced pages (or tear a write in half) under fault, and a
mount-evidence law fails any durability run that cannot prove from
collected artifacts that it really ran on lazyfs. lazyfs is baked into
the env image at the spike's pinned commit and is **inert** for every
pre-M4 scenario. Three results worth a reviewer's attention: (1) the
per-mount cost the spike flagged is gone — 64 MB of cache
pre-allocates in **61 ms** against ~8 s for lazyfs's 1 GiB default, so
five mounts cost ~0.3 s of startup, not ~40 s; (2) the torn-write
fault only fires when its target is named by its **backing** path (the
mountpoint path silently never matches — verified before wiring it,
and worth knowing for anyone who reads lazyfs's README the way I first
did); (3) the metadata probe produced a precise, deliberately-bounded
answer: Ratis fsyncs `raft-meta`'s *contents* before renaming it into
place but never fsyncs the parent *directory*, which is a real
theoretical exposure — and lazyfs **cannot** test it, because it
applies renames straight to the backing store. The 5/5 no-regression
result is therefore a statement about the tool, not a clean bill of
health for Ratis, and the report says so in those words.

## The expectations table (brief deliverable 5 — read this first)

Written before the runs, so "green" and "finding" both mean something.
BACKLOG item 4's rule is the spine: a fault that destroys committed
data existing nowhere else is **out-of-model** — Raft never promised to
survive it, and convicting Ratis for it would be a false alarm.

| Scenario | In model? | What a pass looks like | What a finding would look like |
|---|---|---|---|
| `unsync-drop` (minority: discard un-synced pages, kill, restart) | **In model.** A crash plus loss of data the node never acknowledged as durable. The surviving majority holds every acked write. | Linearizable; no acked write lost; the cluster keeps serving throughout (only a minority is down); recovery needs no operator action. | Any acked write missing after recovery, a stale read, or a node that cannot rejoin. Would mean Ratis acked something it had not synced — i.e. a Ratis fsync bug or a lazyfs fsync-passthrough bug, both reportable. |
| `unsync-drop-all` (every node simultaneously) | **In model for safety, not for availability.** Every node loses only un-synced data — nothing acknowledged is destroyed, because Ratis syncs before acking. This is the quorum-wide shape the spike identified as the only one that reaches Raft's durability assumption. | **Safety**: no acked write lost, no stale read, linearizable. **Availability**: a temporary gap while all five restart is legal and expected — the liveness checker gates the whole fault window. | An acked write lost, or a stale read after recovery. That would be the headline result of this milestone: the durability assumption violated under a fault Raft's model says it should survive. |
| `torn-write` (partial write to one node's raft log, then restart) | **In model.** A torn sector is exactly what a power cut does to an in-flight write; Ratis ships `CorruptionPolicy=EXCEPTION` precisely for it. | Either the node recovers cleanly (truncating at the damaged record) **or** it refuses to start loudly. The other four keep serving; the cluster stays linearizable. | A node that starts and serves *silently wrong* data, or an acked write lost cluster-wide. Silent acceptance of a corrupt log would be the serious finding. |
| Metadata probe (drop un-synced during an election, restart, check `term`) | **Probe, report-only.** | Persisted `term` never regresses. | A regressed `term`/`votedFor` — a node could vote twice in one term, an election-safety defect of the highest class. |
| **Explicitly NOT run** — unequal durable-state loss (deleting synced data on some nodes) | **OUT of model** (BACKLOG 4). It destroys committed data that exists nowhere else. | — | Nothing. A red run here would prove only that we broke the premises; Review 01's log-ablation probe already showed it produces a 60 s+ read outage, which is *correct* behavior under an impossible fault. The nemeses are built so this cannot happen by accident: the torn-write heal **remounts over the same backing store** rather than wiping it, and no scenario touches synced bytes. |

## What was built

| File | One line |
|---|---|
| `env/Dockerfile` | lazyfs built at the spike's pinned commit (`045a0b3a…`) with its spdlog pre-fetch accommodation; build deps purged in-layer; `fuse3`/`libfuse3-3` + `libpcache.so` at runtime; `user_allow_other`. Inert unless mounted |
| `harness/src/ratis_jepsen/db.clj` | mount lifecycle: `mount-lazyfs!` (fresh), `remount-lazyfs!` (preserving the backing store — the torn-write heal), `unmount-lazyfs!`, `drop-unsynced!`, the per-node toml + torn-op injection builders, and `await-mount!`, which fails the run loudly if the mount never appears |
| `harness/src/ratis_jepsen/nemesis.clj` | `unsync-drop`, `unsync-drop-all`, `torn-write` as proper fault/heal pairs (so liveness gates their windows, including the cluster-wide gap); `durability-kinds`; per-kind cycle knobs |
| `harness/src/ratis_jepsen/checker.clj` | the mount-evidence law: every node must show its own `/proc/mounts` line in collected artifacts, else `:no-lazyfs-mount-evidence`; drop acknowledgements counted as fault evidence |
| `harness/src/ratis_jepsen/core.clj` | `--durability` derived from the nemesis kind; the torn-write injection wired at mount time |
| `harness/src/ratis_jepsen/workload/{register,counter}.clj` | mount evidence required for durability kinds only |
| `harness/test/…` | durability vocabulary/segments/cadence, the "no pre-M4 kind requires a mount" regression pin, mount-evidence counting and verdicts |
| `.github/workflows/jepsen.yml` | durability scenario tokens (itemized below) |
| `docs/RUNS.md` | M4 ledger entries |
| `jobs/11-durability-faults/11_report.md` | this report |

TBD-RUNS

## The metadata-durability probe (brief deliverable 4)

**Stated plainly: the hypothesis is real at the source level, the
experiment found no regression, and the experiment could not have
found one. All three facts matter.**

### From source (ratis-3.2.2)

The vote path fsyncs before it acts:

- `RaftServerImpl.requestVote` → `state.grantVote(candidate)` then
  `state.persistMetadata()` (the code's own comment: `// sync
  metafile`) — **before** the reply is built and sent.
- `ServerState.persistMetadata` → `RaftStorageMetadataFileImpl.persist`
  → `atomicWrite`, which writes through `AtomicFileOutputStream` over
  `FileUtils.newOutputStreamForceAtClose(...)`. That stream's `close()`
  ends in **`channel.force(true)`** — contents fsynced, metadata
  included.
- Then `FileUtils.move(tmp, target, REPLACE_EXISTING)` → `Files.move`
  with `ATOMIC_MOVE` prepended → `rename(2)`.

**The gap**: nothing fsyncs the *parent directory* after that rename.
POSIX does not guarantee a rename is durable until the containing
directory is synced, so on a true power cut the metadata update could
in principle be lost while its (fsynced) contents sit in an orphaned
temp file — the classic atomic-rename-without-directory-fsync pattern.
On a journalled ext4 with default `data=ordered` this is unlikely in
practice, but it is not guaranteed by the standard.

### From experiment

Single node, storage on lazyfs, five trials of: run until a term is
persisted → `lazyfs::clear-cache` → `kill -9` → restart → read
`term` from `raft-meta`.

```
trial | term_before | term_backing_before | term_after_restart | regressed?
  1   |      1      |          1          |         2          | no
  2   |      2      |          2          |         3          | no
  3   |      3      |          3          |         4          | no
  4   |      4      |          4          |         5          | no
  5   |      5      |          5          |         6          | no
```

Term advanced monotonically; the backing store always already held the
current term before the fault, which independently confirms the
`force(true)` trace.

### Why that negative proves less than it looks — the control

lazyfs's `lfs_rename` calls the underlying `rename()` **immediately**
and only re-keys its cache pages; renames are never held as un-synced
state. So a `clear-cache` fault cannot revert one. Demonstrated
directly, using Ratis's exact pattern (write temp, fsync the *file*,
rename over the target, never fsync the directory):

```
after rename, through mount: b'term=2\n'
backing before drop:         term=2
--- after clear-cache (the simulated power cut):
through mount:               term=2
backing store:               term=2
```

The un-dirsynced rename survives intact. **Therefore the 5/5
no-regression result is a property of the tool, not evidence that
Ratis is safe against metadata loss.** Testing the real hypothesis
needs a fault injector that models directory-entry durability
(dm-flakey/dm-log-writes at the block layer, or a lazyfs extension);
that is a future job, not something this milestone can claim either
way.

## Deviations from the brief

TBD-DEVIATIONS

## Known gaps and risks

TBD-GAPS

## Suggestions (out of scope)

TBD-SUGGESTIONS

## Environment notes (this execution sandbox, not the repo)

Same accommodations as Jobs 04–10 (uncommitted): dockerd restarted for
this session; image built with `RJ_EXTRA_CA_BUNDLE` (the single proxy
CA) and `RJ_DOCKER_BUILD_ARGS="--network=host --build-arg HTTPS_PROXY=…"`
so the in-image lazyfs build can clone through the egress proxy;
control's `/root/.m2` seeded from the host cache; gnuplot side-loaded
into control.
