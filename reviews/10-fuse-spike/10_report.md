# Review 10 report — the FUSE spike (gates M4)

## Verdict: MERGE

## Justification

The spike's deliverable is evidence quality, and the evidence held up
under independent reproduction: the local loss demonstration works
exactly as transcribed (byte-identical lazyfs binary, both
accommodations needed and effective), my subset-sync probe sharpened it
(selective per-file loss on command), and the load-bearing
sync-before-ack claim is now proven **three independent ways** — I
traced it to `fileChannel.force(false)` in the 3.2.2 source, confirmed
the acked log is md5-identical in the backing store before any fault,
and reproduced clean recovery in both fault orderings. The M4 redirect
(torn writes + quorum-wide drops, not single-node clear-cache) is
well-founded. The one unmet acceptance criterion — no GHA run — is
exactly as the worker documented: I reproduced all three evidentiary
legs of their diagnosis from my own independent session (unregistered
workflow, my own dispatch 404, zero runs from four landed pushes), and
no worker-executable revision exists — the closure needs one
owner-credential action, or simply this PR's merge (which registers
the workflow for dispatch). A REVISE demanding the impossible would be
invalid under the review protocol's own standard; instead the verdict
carries one condition for the coordinator, below.

**Condition on merge (coordinator action):** dispatch
`fuse-spike.yml` immediately after merging (`gh workflow run
fuse-spike.yml --ref main`), read its log, and treat a red run as
reopening M4's runner question before any M4 job is briefed. The
workflow is self-asserting (green ⇔ un-fsynced dropped AND fsynced
survived), so this is one action and one glance.

## What I verified

Environment: worker branch at 61e886c in a read-only worktree;
experiments in an ad-hoc privileged container (`--device /dev/fuse`)
on the **unmodified** `ratis-jepsen/env:latest` image, per the
transcript; ratis 3.2.2 sources from Maven Central for the sync-path
trace.

### Emphasis 1 — the hosted-runner proof (dispatch attempted)

I attempted the dispatch myself, from my own session and credential:

- `actions_list list_workflows`: only `jepsen.yml` is registered —
  `fuse-spike` is absent, exactly as the worker's `list_workflows`
  evidence said.
- `actions_run_trigger run_workflow fuse-spike.yml
  ref=claude/membership-churn-analysis-nn6hlo`: **404**, matching the
  worker's result — GitHub's documented registration rule (a workflow
  becomes dispatchable once it has run once or exists on the default
  branch) blocks *any* caller, not just their token.
- `list_workflow_runs` for the branch: `total_count: 0` despite four
  landed pushes touching the workflow — the push-trigger suppression
  for this session class, reproduced.
- Direct Actions REST through the sandbox proxy: blocked with a
  session-policy message (a variant wording of the worker's observed
  block; same category — the proxy gates the Actions REST surface, the
  MCP server is the sanctioned path).

So the run cannot be produced by the worker, by me, or by any
pre-merge dispatcher. The workflow itself is sound on inspection: the
experiment mirrors the local transcript, the pin matches
(`045a0b3…`), and the two closing assertions make green
self-certifying. Acceptance criterion 2 is honestly unmet for a
mechanical reason external to the work; the compensating evidence for
the CI-viability verdict is (a) privileged containers on hosted
runners are already proven *in this repo* (`jepsen.yml` runs the full
privileged compose), and (b) the FUSE-specific leg is proven locally
under the same base image — with the first post-merge run as the
mechanical closer. One nuance the coordinator should read with care:
the PR body's "same kernel … a hosted runner uses" overstates — the
sandbox kernel (6.18) is not the runner's; same family, not same
kernel. The report's verdict does not otherwise lean on that phrase.

### Emphasis 2 — the local demonstration, reproduced

Followed the transcript verbatim in a fresh container: apt deps →
clone at pin `045a0b3` ("increase MAX_READ_CHUNK", matching) → spdlog
pre-clone + `FETCHCONTENT_SOURCE_DIR_SPDLOG` → libpcache build exit 0
→ the `_deps/spdlog-src` symlink → `./build.sh` exit 0 → binary
`3110656` bytes (**byte-identical size to the transcript**) → mount
via `mount-lazyfs.sh` → `fuse.lazyfs` in `mount`. Every step worked
as written; the only transcript deltas were environmental (my proxy
port; pre-allocation timed at **4.3 s** on my box vs their ~8 s —
same order, machine-dependent, the cost is real). The loss
demonstration reproduced (see the probe below, which subsumes it).

### The probe — subset-sync (review brief's optional sharpener)

Three files through the mount: `a.txt` un-synced, `b.txt` fsynced
mid-stream (pure-fsync via `dd if=/dev/null oflag=append
conv=fsync,notrunc` — no rewrite), `c.txt` un-synced. Before the
fault, the backing root showed exactly `a=0B, b=20B, c=0B` (only the
fsynced bytes durable). After `lazyfs::clear-cache`:

```
a.txt: [0B]  content=<>                    <- dropped
b.txt: [20B] content=<BBB will-be-fsynced> <- survived
c.txt: [0B]  content=<>                    <- dropped
```

Selective, per-file, fsync-respecting loss on command — and, for the
rehearsal below, proof that the tool *does* destroy unsynced data in
this exact mount and session, so "Ratis lost nothing" cannot be the
tool silently no-opping.

### Emphasis 3 — the load-bearing claim, graded

Reproduced the single-node rehearsal end to end (tarball from the
worktree, real `RaftClient` via jshell):

- k1–k5 acked; **the log file was md5-identical through the mount and
  in the backing root at ack time** — stronger than the transcript's
  size comparison: every acked byte was already durable before any
  fault existed.
- Ordering A (clear-cache → kill -9 → restart): recovery log matches
  the transcript's lines (`corruption.policy = EXCEPTION (default)`,
  `Successfully read 11 entries`, `commitIndex: updateToMax`), then
  `GET k1..k5` → all five values.
- k6–k10 acked; Ordering B (kill -9 → clear-cache → restart): the
  two-segment recovery matches (`11 entries` + `12 entries`), then
  `GET k1..k10` → all ten values.

**Evidence strength: strong — the inference is provable, not just
consistent.** The worker's wording ("Ratis fsyncs each append before
acking") is confirmed at source, which lifts the claim above
"no loss observed":

1. `RaftServerConfigKeys.Log`: `UNSAFE_FLUSH_ENABLED_DEFAULT = false`,
   `ASYNC_FLUSH_ENABLED_DEFAULT = false` — and the SUT's production
   profile touches neither.
2. The default flush path (`SegmentedRaftLogWorker.flushIfNecessary` →
   `flushOutStream` → `SegmentedRaftLogOutputStream.flush` →
   `BufferedWriteChannel.flush`) ends in **`fileChannel.force(false)`**,
   and only then advances the flushed index
   (`updateFlushedIndexIncreasingly`) that feeds commit — and hence
   the ack — by construction.
3. The md5 witness above plus my subset-sync probe close the loop
   empirically: acked data was on disk *before* the fault, and the
   fault demonstrably destroys whatever is not.

The M4 consequence — clear-cache on one node cannot manufacture
acked-write loss; the signal must come from torn writes and/or
quorum-wide drops — follows soundly. The recommended M4 shape (mount
at `db/setup!` over a renamed backing dir, per-node fifo trigger,
`:lazyfs-lose-majority` flagship, drop-count evidence law, counter
workload as probe) is consistent with everything Jobs 07–09
established; I have no corrections to it.

### Emphasis 4 — the two accommodations

1. **spdlog pre-fetch behind the proxy**: needed and effective exactly
   as written — the proxy 403s GitHub archive tarballs, the pre-clone +
   `FETCHCONTENT_SOURCE_DIR_SPDLOG` + symlink dance built cleanly on
   the first try. Accurately characterized (and correctly scoped:
   sandbox-only; runners fetch tarballs directly).
2. **Per-mount pre-allocation**: real and load-bearing for M4's
   budget — 1 GiB pre-allocated per mount (`pre-allocating 1073741824
   bytes` in my mount log), 4.3 s here vs their ~8 s (machine
   variance). The report's own mitigation (shrink
   `cache.simple.custom_size` per node) is the right one; at defaults,
   five nodes would consume 5 GiB of runner RAM — the report's warning
   is accurate.

### Emphasis 5 — hygiene

Diff is exactly the two granted files; the workflow carries the
Apache-2.0 header and a loud SPIKE ARTIFACT / THROWAWAY banner;
`workflow_dispatch` is retained with the branch-push bootstrap as a
documented deviation mirroring Job 06's precedent (`b1aa0b8`); lazyfs
pinned by full commit hash in both the workflow and the report; no
committed experiment artifacts.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking (verdict condition) | `.github/workflows/fuse-spike.yml` | Acceptance criterion 2 (GHA run linked, green) is unmet for reproducible mechanical reasons outside any agent's control; the merge itself registers the workflow for dispatch. The coordinator should fire it immediately post-merge and treat a red run as reopening M4's runner choice. |
| 2 | non-blocking | PR #21 body | "The same kernel … a hosted runner uses" overstates the local proof's equivalence — same kernel *family* and arch, not the runner's kernel. The report proper is more careful; the verdict survives on the in-repo privileged-CI precedent plus the self-asserting first run. |
| 3 | non-blocking (observation) | `jobs/10-fuse-spike/10_report.md` (deliverable 3) | The rehearsal's evidence can be stated one grade stronger than the report claims: the sync-before-ack behavior is source-provable (`force(false)` before the flushed-index advance, both unsafe/async flush defaulting off), not only inferable from recovery. Worth folding into the M4 design note — it also means any future M4 run that *does* lose an acked write on a single node has found either a Ratis fsync bug or a lazyfs fsync-passthrough bug, both reportable. |

## Suggestions (non-blocking)

- When M4 lands, keep the report's own suggestion to bake lazyfs into
  the env image — my reproduction spent ~3 minutes on the build that
  would be image-cached.
- M4's torn-write sub-spike (the report's Known gaps flags
  `torn-seq`/`torn-op` as untested) should reuse this spike's exact
  container recipe; the subset-sync probe here is the natural template
  for its assertion shape.
- The pure-fsync trick (`dd if=/dev/null of=<file> oflag=append
  conv=fsync,notrunc`) is handy for M4's "persist a chosen subset"
  scenarios and avoids lazyfs's checkpoint command; consider it for
  the nemesis toolbox.

## Verification notes

- Worker branch consumed read-only (`git worktree add
  ../job-10-under-review 61e886c`); nothing pushed to it.
- The dispatch attempt, registration check, and zero-runs check ran
  from this review session's own credential — independently
  reproducing the worker's three-part diagnosis rather than taking it
  on faith.
- My rehearsal used a fresh container and the worktree-built SUT
  tarball; one self-inflicted `pkill -f` mishap mid-sequence killed my
  own exec shell (pattern matched it), after the fault ordering had
  already landed — noted for transcript honesty; the ordering-A
  sequence (drop → kill → restart) was preserved and its results are
  the ones reported.
