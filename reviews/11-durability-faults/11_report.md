# Review 11 report — M4: durability faults via lazyfs

## Verdict: MERGE

## Justification

Every acceptance criterion reproduced independently: suites, all three
nemeses with their stated expectations met (my torn-write run fired a
genuine 5-byte partial write and drew the loud `ChecksumException`
refusal with its own fresh values — an independent reproduction, not a
replay), the negative arm's distinct `DURABILITY MOUNT UNPROVEN` abort,
the counter crossover holding exactly-once, the regression run inert to
the byte, and the metadata probe re-run PASS. The three lazyfs-defect
claims and the whole metadata source chain check out at source. The
one substantive thing this review adds is a **probe-power result the
worker could not have seen**: I planted the very defect the metadata
probe hunts (an fsync-lying SUT via `LD_PRELOAD`) and the probe
**printed PASS while the planted defect destroyed the vote metadata**
— its decision rules skip unparseable samples, so divergence-by-absence
(153 of 237 samples in my planted run) counts as zero. The saving
grace, also established here: the absence-shaped manifestation is
fail-safe (Ratis refuses to start on an empty `raft-meta` —
`getTerm → orElseThrow`), while the genuinely dangerous
revote-hazard shape (an *old but parseable* term) **is** counted by the
rule (synthetically proven). The metadata conclusion therefore stands
— on the source proof plus the covered channel — but the probe needs a
two-line hardening and the external framing needs one sentence of
honesty before the result ships anywhere. Non-blocking, precisely
because the uncovered shape is the fail-safe one; findings below.

## What I verified

Environment: worker branch `claude/durability-faults-job-t7u5my` at
db02478 in a read-only worktree; env image rebuilt from the worktree
(lazyfs stage included); ratis 3.2.2 sources from Maven Central;
lazyfs source cloned at the pin for the defect verification.

### Emphasis 1 — the metadata result, held to positive-claim standards

**Source half, independently re-read** — every link at 3.2.2:

- `RaftServerImpl.requestVote` (1464–1466): `state.persistMetadata();
  // sync metafile` inside the synchronized block on
  `termUpdated || voteGranted`, before the reply proto is built;
  `changeToFollowerAndPersistMetadata` (635–638) same discipline.
- `ServerState.initElection` ELECTION phase (238–241):
  `currentTerm.incrementAndGet(); votedFor = self; persistMetadata();`
  before any vote request goes out.
- `RaftStorageMetadataFileImpl.atomicWrite` (75–84) → Properties
  through `AtomicFileOutputStream` (its stream is
  `FileUtils.newOutputStreamForceAtClose`, whose `close()` does
  **`channel.force(true)`**) → then `FileUtils.move(tmp, raft-meta,
  REPLACE_EXISTING)`. Durable-at-file-level before acting: confirmed.
- **No parent-directory fsync after the rename** — confirmed absent in
  `FileUtils.move`/callers. The caveat is real at source level.

**Experiment half, re-run**: probe PASS across 3 cycles in my
environment (terms 2→4, 250/170/189-shape sampling ≈ 600+ samples,
zero divergence, no term regression) — the worker's 609-sample result
reproduces.

**Probe power — the review brief's core demand — tested by planting
the defect.** I compiled an `LD_PRELOAD` shim that makes
`fsync`/`fdatasync` lie (return 0, sync nothing), restarted one voter's
SUT under it, and ran one probe cycle against that victim:

- The planted defect manifested exactly as modeled: the victim voted
  at term 5, its **backing `raft-meta` went empty** (the tmp content
  never persisted before the rename), and after the cache-drop the
  metadata was destroyed — `probe: survives power loss []`.
- **The probe printed `VERDICT: PASS`.** Two decision-rule blind
  spots: the divergence counter requires *both* terms parseable
  (`-n "$mt" && -n "$bt"`), so the 153-of-237 samples reading
  `MOUNT[term=5 votedFor=n2] BACKING[]` counted zero; and the final
  regression check skips when the recovered term is unparseable
  (`recovered=?` → "no regression").
- **Countervailing facts, also established here:** (a) the
  absence-shaped manifestation is fail-safe — the victim refused to
  start (`RaftStorageMetadataFileImpl.getTerm → orElseThrow` on the
  empty file), and a node that cannot start cannot double-vote; (b)
  the dangerous shape — an *old but parseable* term surviving, i.e.
  the rename-lost/partial-persist path that permits an actual re-vote
  — **is** detected by the rule (fed synthetically through the exact
  sed/compare pipeline: `mt=5 bt=4 → DIVERGENT`).

**Grading**: the worker's conclusion (term/votedFor durable before the
node acts; no double-vote class at 3.2.2) **stands** — the source
proof is airtight and the probe's covered channel is the
safety-relevant one. But the report's "confirmed by a 609-sample
experiment" overstates what the experiment could have detected: as
shipped it cannot flag the absence manifestation at all. Finding 1
specifies the two-line hardening; until it lands, the external framing
should say "source-proven; probe-consistent (the probe detects the
parseable-divergence shape; the absence shape manifests as a safe
startup refusal)".

**The parent-dir caveat, judged** (the review brief delegates this):
it belongs in the backlog as an upstream *question*. The gap is real
(POSIX does not make a rename durable without a directory fsync;
etcd/ZooKeeper/LevelDB sync the parent for exactly this; the
crash-consistency literature documents filesystems losing un-fsynced
renames), the window is one rename per vote/term-change, and the
worst case — recovering the *previous* raft-meta with an older
term/vote — is precisely the re-vote hazard. It is genuinely outside
lazyfs's model (renames pass through), so this harness cannot
demonstrate it; a dm-flakey/CrashMonkey-style follow-up could. That
combination — real mechanism, undemonstrable here, highest-severity
class if real — is exactly what a backlog upstream-question entry is
for. My planted-defect run adds one supporting datum: when the meta
file is *empty* Ratis fails safe, so the question narrows cleanly to
the old-content case.

### Emphasis 2 — torn-write

My gate run reproduced the whole chain with fresh values (store
`…register-torn-write/20260807T120346.105Z`):

- **The tear genuinely fired mid-write**: n2's lazyfs log —
  `Write to path …/current/log_inprogress_0: will persist 5 bytes
  from offset 49613` then `Killing LazyFS pid 840!`. A 5-byte head of
  a larger append persisted; the rest died with the cache.
- **The refusal is caused by the tear**: n2's restart reads the
  segment to the torn entry and dies on
  `ChecksumException: Calculated checksum is 23FF9958 but read
  checksum is 00000000` → `Failed to initRaftLog` under
  `corruption.policy = EXCEPTION (default)` — the checksum bytes fell
  in the dropped tail. History records
  `:outcome :refused-start, :fired true` with the log tail.
- **Majority linearizable throughout**: 1078/422, every checker
  `:valid? true`, liveness gated.
- **The lazyfs defects are real and accurately characterized** — all
  three verified in lazyfs source at the pin: the fifo torn-op
  attribute loop knows only file/parts/parts_bytes/persist and trips
  "unknown attribute" on `occurrence=` (main.cpp 260–263), the
  success log tests only `errors_add_torn_op` — empty precisely
  because the invalid fault never ran — so "configured successfully"
  logs for dropped faults (271–276), and `add_torn_op_fault` hardcodes
  `int occurrence=1` (lazyfs.cpp). Upstream-able to dsrhaslab/lazyfs
  as the worker suggests.

**Distribution probe (review brief)**: two more torn-write runs, both
green — both fired (`:fired true`) and both drew `:refused-start`
(stores `…torn-write/124857.763Z`, `…124959.873Z`). Across all three
runs in my environment (gate + two probes) the outcome is **3/3
loud-refusal, 0 clean recovery, 0 silent-wrong-data** at the default
`persist-part 1` under this workload — a stable distribution. For any
public claim: at these write shapes the torn write reliably produces
the fail-safe refusal arm; the clean-recovery arm remains unobserved
(consistent with the worker's deviation 4), and "never silently wrong"
is the empirically stable half.

### Emphasis 3 — unsync-drop-all sizing

- **The `:info` population is inherent, not a mapping defect**:
  confirmed. Writes invoked during a five-node outage are honestly
  ambiguous (the dead leader may have appended them; they can commit
  after restart — the same Review-05 soundness rule every kind obeys),
  and my run's 118 `:info` cluster at startup and inside/immediately
  after the four gated windows, with calm phases clean and liveness
  `:valid? true`.
- **The pinned shape exercises a real fault window**: 20 clear-cache
  acks = 4 whole-cluster losses × 5 nodes; every acknowledged write
  survived; analysis completed in **17 s** on my box (the worker's
  8.2 s on theirs — same order, the heaviest analysis in the suite
  either way, still far from the OOM cliff whose two preserved stores
  document the naive shapes).
- **Boundary probe**: the same schedule with `--key-count 5` — two
  workers per key, the worker density of the second preserved OOM
  shape at the shipped calm — completed **green with analysis at
  4.2 s** (store `…unsync-drop-all/125100.583Z`; 20 acks = 4 windows,
  51 `:info`, per-key `:info` mass ≈ the shipped shape's). The pinning
  is therefore not perched on the cliff edge: a modestly denser shape
  still completes comfortably; the preserved OOMs came from the
  *combination* of short calm (more windows) and doubled workers, and
  the shipped shape has genuine margin on both axes.

### Emphasis 4 — evidence law, off-path, regression

- **Mounts proven per node**: five `durability mount proven` lines in
  every durability run's jepsen.log (mount-table type + fault fifo +
  fsync'd canary observed in the backing dir — the canary uses the
  pure-fsync append so nothing is rewritten).
- **Negative arm reproduced**: n3's lazyfs binary replaced with an
  exit-0 stub → the run aborts in setup with the distinct
  `DURABILITY MOUNT UNPROVEN on n3 (:mount-await): no fuse.lazyfs
  mount … within 60000 ms` error; binary restored; the next durability
  deployment proved all five mounts again.
- **Regression run**: `--nemesis partition` (durability off) green
  with all checkers valid; on-node inertness confirmed (zero
  `fuse.lazyfs` mounts, no backing dir, no lazyfs log; the store's
  per-node dir carries only `ratis-kv.log`); numbers in family with
  every prior partition ledger entry. The disclosed `results.edn`
  shape delta (the informational all-zero `:durability-evidence` map)
  is exactly the Jobs 07/08 compose-unconditionally pattern.

### Emphasis 5 — expectations discipline

The deliverable-5 table states, per scenario, in/out-of-model, what a
pass looks like, and what a finding would look like — before any run
is quoted; each ledger entry then judges its run against the stated
expectation. The BACKLOG-4 boundary is handled correctly: the model
note explains why `clear-cache` cannot destroy fsynced (acknowledged)
state by construction and why the torn write is single-node
un-fsynced-tail damage — no scenario destroys durable state unequally
and then convicts. The deliberately-not-built list (synced-state
drops; durability kinds in mixed-all) is argued from the same model.
Discipline: met.

### Emphasis 6 — crossover

Counter × unsync-drop: green; exactly-once held under storage faults
(per-key bounds + observed-total pinning + duplicate-total assertion
all valid); retry evidence 13 `:info`-adjacent retried ops with
nonzero totals; 17 clear-cache acks. (My run: 17 acks vs the worker's
15 — same order; their retry total 201/78 vs my equivalent-magnitude
counts.)

### Emphasis 7 — costs and diffs

- **env diff confined**: the lazyfs build stage (pinned commit,
  spdlog pre-fetch, amd64-only per PLAN Q8) + runtime `libfuse3-3`/
  `fuse3`/`user_allow_other` + README section. Nothing else.
- **Workflow diff**: description-only token documentation; default
  sweep unchanged — matches the deviation note.
- **No `sut/**` changes**: confirmed in the diffstat.
- **Costs honest**: my cold image rebuild paid the lazyfs stage
  (~2 min class, matching the claimed 1 m 54 s); durability DB setup
  visibly adds seconds per run (mount + prove on five nodes) with run
  walls in the 315–320 s class for 300 s limits — consistent with the
  claimed 6–8 s vs 4.7 s setup. The 128 MiB cache sizing rationale
  (must exceed the touched byte-set or lazyfs writes through and
  defuses the fault) is documented on `db/lazyfs-cache-size` and is
  the kind of trap note that will save someone a silent no-op run.

### Suites

- Harness: **107 tests / 958 assertions, 0 failures** (the report's
  criterion-1 text says "951" — stale; its own file table says
  107/958, which matches my run; finding 3).
- SUT: green, unchanged sources.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking (required before external quotation) | `harness/scripts/metadata-probe.sh:205-214, 232-238` | The probe's decision rules cannot flag the absence-shaped manifestation of the defect it hunts: the divergence counter skips samples whose backing term is unparseable (my planted-defect run: 153/237 samples read `MOUNT[term=5…] BACKING[]` and counted zero), and the regression check skips an unparseable recovered term — so a planted fsync-lying SUT produced `VERDICT: PASS`. The dangerous parseable-old-term shape IS detected (synthetically proven), and the absence shape fails safe (Ratis refuses to start on empty meta), so the headline conclusion stands — but harden both rules (count mount-parseable + backing-unparseable as divergence; treat an unparseable/absent recovered term or a failed victim restart as a finding) and, until then, frame the result as "source-proven; probe-consistent" rather than "experimentally confirmed". |
| 2 | non-blocking | `jobs/11-durability-faults/11_report.md` (metadata section), ledger | Add the fail-safe datum this review established to the record when the coordinator absorbs the result: an *empty/unparseable* raft-meta refuses startup (`getTerm → orElseThrow`), so the upstream parent-dir-rename question narrows to the old-content case — which strengthens the backlog entry's precision. Endorsed for the backlog as an upstream question. |
| 3 | non-blocking | `jobs/11-durability-faults/11_report.md` criterion 1 | "107 tests containing 951 assertions" is stale; the branch runs 107/958 (the report's own file table has it right). Same class as Review 09 finding 4. |
| 4 | non-blocking (observation) | `harness/src/ratis_jepsen/nemesis.clj` (durability docstring) | My drop-all analysis took 17 s vs the worker's 8.2 s on the same shape — machine-dependent and still bounded, but the margin to the knossos cliff varies ~2× by host; the elle migration the worker suggests would retire this class of tuning entirely. |

## Suggestions (non-blocking)

- Apply finding 1's probe hardening in the next touching job; it is
  two guard changes and a re-run.
- The worker's upstream list is right: the two lazyfs fifo bugs (now
  triple-verified in source by this review) and the Ratis parent-dir
  question. My planted-run narrowing (finding 2) belongs in the
  latter's write-up.
- When the coordinator dispatches the durability CI tokens the first
  time, watch the lazyfs image-stage cost on hosted runners against
  the 1 m 54 s local figure — if it exceeds the run budget's
  tolerance, a prebuilt-layer cache is the fix the worker already
  sketched.

## Verification notes

- Worker branch consumed read-only (`git worktree add
  ../job-11-under-review db02478`); nothing pushed to it.
- The planted-defect run used an `LD_PRELOAD` fsync-no-op shim
  compiled in my session scratchpad, applied to one node of a
  `--leave-db-running` deployment, and removed afterwards; the shim
  never touched the repo, the worker's code, or any gate run.
- Same uncommitted proxy/environment accommodations as Reviews 08–10;
  the env image was rebuilt from the worktree with the new lazyfs
  stage through the session proxy (the spdlog pre-fetch accommodation
  exercised and effective).
