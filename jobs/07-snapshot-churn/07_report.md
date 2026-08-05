# Job 07 report — M2 part 1: snapshot churn, leadership transfer, follower reads

## Summary

The harness now forces and *proves* Ratis's highest-defect-density path:
`--nemesis snapshot-churn` drives kill-a-follower → leadership-transfer →
snapshot-and-purge → restart cycles, and a new install-snapshot evidence
checker fails any dedicated churn run whose node logs contain zero
install-snapshot activity — a churn run that tested nothing is a broken
test, not a green one. Getting there produced this job's central
discovery: **the brief's cycle (kill, writes, snapshot, restart) cannot
reach install-snapshot at all on this SUT** — Ratis purges only *closed*
log segments, segments close only at 8 MB or on a *term change*, and the
`purge.gap = 1024` floor spaces real purges out — so the shipped cycle
embeds a leadership transfer (the term bump that closes segments), the
transfer excludes the sitting leader via the log census (transfer-to-self
"succeeds" without changing terms), and a new `--rate` flag lets churn
runs sustain writes across the purge-gap milestones instead of burning
the op budget in the first ~25 s. All of this is source-verified at
ratis-3.2.2 and was confirmed by observation before the gates ran.
`--nemesis transfer` and `--reads leader|follower|mixed` (linearizable
follower reads via `sendReadOnly(msg, peerId)`) landed as specified;
`mixed-all` interleaves all five fault kinds. The transfers also
uncovered a second never-reachable-before path: write-path
`LeaderSteppingDownException` had no outcome-map row, and its
pessimistic-`:info` flood drove knossos out of memory in one gate run —
source triage pinned it as a pre-append admission rejection, so it is
now a definite `:fail` row (unit-tested; the OOM'd run preserved). One
live-territory observation to look at (not a conviction): during
follower reboot the leader retries InstallSnapshot with no backoff —
~400 attempts in 15.6 s answered by `ServerNotReadyException` before
converging cleanly.

## What was built

| File | One line |
|---|---|
| `harness/src/ratis_jepsen/nemesis.clj` | churn nemesis (`:churn-kill`/`:churn-transfer`/`:churn-snapshot`/`:churn-restart` — census-picked follower kill, census-excluded transfer target, per-live-server snapshot trigger, restart-all heal), transfer nemesis (`:transfer`), kinds += `snapshot-churn`\|`transfer`\|`mixed-all`, configurable churn/transfer cadences, five-kind `mixed-all` interleave |
| `harness/src/ratis_jepsen/client.clj` | `--reads` targeting (follower-targeted linearizable reads via `sendReadOnly(msg, peerId)`, best-effort leader exclusion, `:read-via` recorded on ops), admin interop (`snapshot-create!` per-server, `transfer-leadership!`), `node-addresses` helper |
| `harness/src/ratis_jepsen/checker.clj` | install-snapshot evidence checker: counts observed leader-send/follower-receive log lines from the snarfed store copies; zero evidence in a dedicated churn run ⇒ `:valid? false`, `:error :no-install-snapshot-evidence` |
| `harness/src/ratis_jepsen/workload/register.clj` | `--rate` (ops/s per worker, default 10 = M0 behavior); evidence checker composed with `:require-evidence?` set for `--nemesis snapshot-churn` |
| `harness/src/ratis_jepsen/core.clj` | `--nemesis` grows the three kinds; `--reads`; `--rate`; churn/transfer cycle flags |
| `harness/src/ratis_jepsen/outcome.clj` | new row: `LeaderSteppingDownException` ⇒ definite write `:fail` (pre-append admission rejection, single throw site at 3.2.2 — first reachable via this job's transfers; see "The second discovery") |
| `harness/test/ratis_jepsen/nemesis_test.clj` | vocabulary, churn/transfer segment shapes + configurable cadence, five-kind mixed-all interleave, package routing |
| `harness/test/ratis_jepsen/checker_test.clj` | evidence counting against the *observed verbatim* log lines, zero/nonzero verdicts, churn-ops gating |
| `harness/test/ratis_jepsen/client_test.clj` | follower-candidate selection |
| `.github/workflows/jepsen.yml` | the granted single line: `scenarios` default now lists all eight kinds |
| `docs/RUNS.md` | M2 part-1 ledger entries |
| `jobs/07-snapshot-churn/07_report.md` | this report |

## The mechanism triage (read this first)

The brief says: *"client-triggered snapshots + purge.upto.snapshot.index=true
(already the SUT profile) + a held-back follower ⇒ recovery must go
through install-snapshot."* Reality, established by two failed shakedowns
and settled against ratis-3.2.2 source:

1. **Purge drops closed segments only.**
   `SegmentedRaftLogCache.purge` iterates the closed-segment list; the
   open segment is untouchable. A segment closes when it reaches
   `raft.server.log.segment.size.max` (8 MB default — unreachable for
   this workload's ~30-byte entries) or when an appended entry's **term
   differs** (`SegmentedRaftLog.appendEntryImpl` rolls the segment).
   Shakedown 1 (the brief's cycle verbatim): three churn cycles,
   snapshots reporting success at index ~1312, **zero purges, zero
   installs** — the whole run lived in one open segment and the evidence
   checker failed the run exactly as designed (exit 1,
   `:no-install-snapshot-evidence`, store
   `…snapshot-churn/20260805T165902.318Z`).
2. **So the cycle now embeds a leadership transfer** between kill and
   snapshot: the term bump closes every node's open segment, the
   entries the dead follower is missing land in purgeable territory,
   and the snapshot's purge moves the leader's log start past the
   follower.
3. **Transfer-to-self is a silent no-op** — it returns success without
   an election, defeating the roll. The admin client's `getLeaderId` is
   null before its first request, so shakedown 2's first cycle
   transferred to the sitting leader; the fix excludes the leader via
   the `db.clj` log census (primary) plus `getLeaderId` (fallback).
   Shakedown 2 also demonstrated the second gate:
4. **`purge.gap = 1024` (server default) floors purge spacing, and a
   gap-blocked attempt still advances `purgeIndex`** (observed:
   `purgeIndex: updateToMax old=-1, new=1313` with nothing truncated).
   Installs therefore happen at ~1024-index milestones of *sustained*
   write traffic —
5. **and the M0 workload burns its whole 1500-op budget in ~25 s**
   (~55 entries/s at `stagger 1/10` × 10 workers), leaving every later
   cycle's follower missing nothing. The new `--rate` flag (default 10,
   M0 behavior unchanged) lets churn runs run ~14 ops/s aggregate for
   the full 300 s (`--rate 1.4 --ops-per-key 800`), crossing ~2 purge
   milestones per run — each an install-snapshot event.

Shakedown 3 (all fixes): exit 0, two install events —
`followerNextIndex = 1048 but logStartIndex = 1103` (the 400-retry
storm below) and `followerNextIndex = 2447 but logStartIndex = 2506`
(single-shot clean install). The pinned evidence patterns are these
observed phrasings; the `notifyInstallSnapshot` wording the API name
suggests never occurs for our direct-streaming state machine.

## The second discovery: LeaderSteppingDownException had no outcome row

The first `snapshot-churn` gate attempt at 300 s exposed a second
never-reachable-before-M2 path: my churn run 2 exited **2**
(`:valid? :unknown`) after a 924 s wall — knossos ran out of memory on
key 0. The trigger: the run's history carried **147
`LeaderSteppingDownException` completions**, an exception the outcome
map had no row for (nothing transferred leadership before this job), so
each landed in the unknown-throwable pessimism bucket — `:info` + loud —
and the `:info` flood pushed the linear checker over its documented
memory cliff. Triage against ratis-3.2.2 source: the exception has a
**single construction site**, the pre-append admission check
(`RaftServerImpl.checkLeaderState`) a transferring leader applies to new
write requests — the request is *never appended* (pending appended
requests are completed with `NotLeaderException` instead, which is
exactly why the Review-05 amendment keeps *that* row `:info` while this
one is a definite `:fail`). Reads cannot receive it at all
(`isReadOnly()` skips the check). The new row + unit tests landed, and
the re-run went green with sub-second analysis (criterion 2 table). The
OOM'd run is preserved:
`…snapshot-churn/20260805T172714.309Z` (its own checkers that did
complete — stats, liveness, evidence — were all valid; only the
linearizability analysis died).

## Live-territory observation (brief's "hunting" clause)

During shakedown 3, follower n2 restarted while the leader n5 already
wanted to install: n5 retried InstallSnapshot **~400 times in 15.6 s**
(~25/s, no backoff), every attempt answered
`ServerNotReadyException` from n2's still-initializing division, then
the install proceeded normally in 5 s chunks and the run was green end
to end. Not a wedge (converges; RATIS-2500's infinite loop does not
apply), but the retry rate during follower boot is unthrottled — on a
slow-booting follower (large snapshot, cold cache) this would spam
thousands of failed installs. Preserved in store
`…snapshot-churn/20260805T171416.540Z` (n2/n5 logs). Suggested below as
an upstream-worthy observation once we have a cleaner repro; no SUT or
harness defect.

## How it was verified

Versions: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`,
JDK 21. Commands from the repo root.

### Criterion 1 — `clojure -M:test` green

```
$ cd harness && clojure -M:test
Ran 61 tests containing 652 assertions.
0 failures, 0 errors.        (exit 0)
```

Was 53/624 after Job 05 revision 1. New: churn/transfer segment and
cadence tests, five-kind mixed-all interleave
(`mixed-all-interleaves-all-five-kinds`), evidence
counting/verdict/gating (`install-snapshot-evidence-counting` carries
the observed verbatim log lines as fixtures — zero and nonzero cases),
`follower-candidates-selection`, and `row-leader-stepping-down` (the
outcome-map row this job's transfers made reachable — see "The second
discovery" below). No regressions.

### Criterion 2 — snapshot-churn ×2 green with evidence counts

`env/run.sh test --nemesis snapshot-churn --rate 1.4 --ops-per-key 800
--time-limit 300`, twice:

| Run | Exit | Wall | Analysis | ok / fail / info | Evidence | Store (`20260805T…`) |
|---|---|---|---|---|---|---|
| churn #1 | 0 | 318 s | 0.8 s | 1531 / 550 / 5 | 4 lines = 2 install events | `…snapshot-churn/172157.232Z` |
| churn #2 | 0 | 320 s | 0.8 s | 1479 / 616 / 0 | 4 lines = 2 install events | `…snapshot-churn/180734.533Z` |

Run #1's first event, verbatim from the node logs (leader-send then
follower-receive; the checker counts exactly these phrasings):

```
2026-08-05 17:24:17.537 [n4@group-ABBC16E54704->n3-GrpcLogAppender-LogAppenderDaemon] INFO
  org.apache.ratis.grpc.server.GrpcLogAppender - n4@group-ABBC16E54704->n3-GrpcLogAppender:
  followerNextIndex = 1133 but logStartIndex = 1151, send snapshot
  SingleFileSnapshotInfo(t:6, i:1192):[/var/lib/ratis-kv/…/sm/snapshot.6_1192] to follower
2026-08-05 17:24:17.584 [grpc-default-executor-0] INFO
  org.apache.ratis.server.impl.SnapshotInstallationHandler - n3@group-ABBC16E54704:
  receive installSnapshot: n4->n3#0-t6,chunk:3e1b2d99-44ea-4910-a9c7-9db55c769bee,0
```

Second event: `n5→n3` at t≈285 (term 12,
`followerNextIndex = 2349 but logStartIndex = 2377`) — the two events
sit exactly at the run's two purge.gap milestones, as the mechanism
triage predicts. `:install-snapshot-evidence {:valid? true, :total 4,
…}` in both results files.

### Criterion 3 — evidence-assertion negative proof

Two independent proofs:

- **A real defanged run**: shakedown 1 ran the brief's cycle (no
  transfer step — structurally unable to install) and the checker
  failed it: exit 1, `:install-snapshot-evidence {:valid? false,
  :error :no-install-snapshot-evidence, …}` (store
  `…snapshot-churn/20260805T165902.318Z`, preserved).
- **Unit fixture** (`checker-test/install-snapshot-evidence-counting`):
  a zero-evidence log set under `required? = true` yields the distinct
  error; the nonzero fixture uses the observed verbatim lines.

### Criterion 4 — transfer, follower-reads-under-partition, mixed-all, churn seeded-red

| Run | Exit | Wall | Analysis | ok / fail / info | Store (`20260805T…`) |
|---|---|---|---|---|---|
| transfer | 0 | 323 s | 0.5 s | 1088 / 408 / 4 | `…transfer/174240.178Z` |
| partition + `--reads mixed` | 0 | 317 s | 0.5 s | 1008 / 491 / 1 | `…partition/174801.489Z` |
| mixed-all | 0 | 321 s | 0.6 s | 1117 / 383 / 0 | `…mixed-all/175840.480Z` |
| churn + `--seed-bug stale-reads` | **1** | 320 s | 1.0 s | 1492 / 527 / 97 | `…snapshot-churn-seedbug-stale-reads/175317.732Z` |

- **transfer**: ~13 transfers, every handover either succeeded or was
  recorded (`{:target …, :result …}` in the history); all checkers
  valid.
- **follower reads under partition**: 234 of the run's reads went
  follower-targeted (`:read-via` spread n1:52 n2:36 n3:61 n4:60 n5:25),
  through partition cycles, and the per-key linearizability analyses
  passed — the follower-read path claims full linearizability and was
  judged so.
- **mixed-all**: drew 1 churn / 1 crash / 4 pause / 3 partition /
  1 transfer segments; liveness valid; evidence reported (0, not
  required — see Deviations 4).
- **churn seeded-red**: convicts **all five keys** while the churn
  cycles run — 2 install-snapshot events landed during the seeded run
  itself. (Its 97 `:info`s are LSDE completions: this run executed
  before the new outcome row landed; conviction is unaffected — the
  seeded stale reads are `:ok`s.) Key 0's violating pair, verbatim:

  ```
  {:op {:process 0, :type :ok, :f :write, :value 0, :index 207, :time 15945464666}}
  {:op {:process 0, :type :ok, :f :read,  :value 3, :index 213, :time 16092558206},
   :model #knossos.model.Inconsistent{:msg "can't read 3 from register 0"}}
  ```

### Criterion 5 — analysis time and `:info` sanity

Analysis stayed sub-second on every green run (0.5–0.8 s; the seeded
red at 1.0 s) — the `--rate 1.4` slowdown plus the LSDE row keeps
`:info` counts at 0–5 despite the 800-op keys, so the knossos cliff
stays distant (contrast the preserved first churn-#2 attempt: 147
unclassified LSDE `:info`s → out-of-memory at 924 s wall). `:info`
sanity per the established method: churn #1's 5 `:info` completions all
inside `:churn-kill`→`:churn-restart` windows, zero in calm phases;
churn #2, mixed-all: zero `:info` anywhere; transfer: 4 (harness
timeouts riding leadership handovers); reads carry zero `:info` in
every run.

### Criterion 6 — headers, ownership, workflow diff

Apache-2.0 headers intact on all touched files (no new files this job —
all namespaces existed). `git diff` against `main`: `harness/**`, one
line in `.github/workflows/jepsen.yml`
(`default: none,partition` → all eight kinds), `docs/RUNS.md` append,
this report.

## Deviations from the brief

1. **The churn cycle contains a leadership transfer the brief didn't
   specify.** The brief's kill→writes→snapshot→restart cycle cannot
   reach install-snapshot on this SUT (mechanism triage above,
   source-verified, demonstrated by shakedown 1's checker-failed run).
   The transfer is the smallest in-scope addition that makes the
   brief's stated intent ("recovery *must* go through install-snapshot")
   true; cadence stays configurable and the step is prominently
   documented in the nemesis docstring.
2. **Evidence is read from the snarfed store logs, not via
   `jepsen.control` grep as the brief suggested.** Checkers run after
   `db/teardown!`, and teardown wipes `/var/log/ratis-kv.log` on the
   nodes; the store copies jepsen snarfs before teardown are the only
   log source that still exists at analysis time. Same evidence, only
   possible mechanism.
3. **Churn gate runs use `--rate 1.4 --ops-per-key 800`** (defaults
   unchanged: rate 10, 300 ops/key). DESIGN 2.5's knossos budget pins
   ≤400 ops/key *for the default partition workload*; the churn runs
   need ~2700 sustained log entries to cross two purge-gap milestones,
   and 800-op keys with the M1 retry policy's near-zero `:info` counts
   analyze in well under a second (numbers below). Flagged for the
   coordinator as a DESIGN §2.5 note rather than silently changed
   defaults.
4. **Evidence is required only for `--nemesis snapshot-churn` runs.**
   `mixed-all` composes churn segments for fault diversity, but its
   churn share cannot reliably cross the purge gap in 300 s; requiring
   evidence there would fail healthy runs. Counts are still reported
   for every run.

## Known gaps and risks

- **Install count per run is purge-gap-bound (~2 per 300 s run).** The
  server's `purge.gap = 1024` cannot be changed without touching
  `sut/**`. If M2+ wants install-snapshot *storms* (RATIS-2500-style
  pressure), the SUT profile needs `raft.server.log.purge.gap` lowered
  and/or `segment.size.max` shrunk — a one-line SUT config change that
  belongs to a Job 08+ brief (suggested below).
- **The evidence checker counts log lines, not distinct install
  events.** The 400-retry storm counts as 400 `:send` lines. For the
  \"did the path run at all\" question this over-counting is harmless
  (zero remains the failure condition), but the counts are not a
  metric of install health; the ledger quotes both counts and event
  interpretation.
- **`:churn-snapshot` on a node that is mid-election can fail** — the
  per-node result records the error and the cycle continues; the purge
  that matters is the (new) leader's, which the snapshot trigger
  reaches on the next cycle at the latest. Observed once across all
  shakedowns; harmless.
- **Follower-read mode relies on `RaftClient.getLeaderId` belief** for
  excluding the leader; a stale belief sends some "follower" reads to
  the leader. They remain linearizable reads either way; the dilution
  is bounded and `:read-via` in the history makes it measurable
  (~470/500 reads went to genuine followers in the shakedown).

## Suggestions (out of scope)

- **SUT snapshot-pressure profile (Job 08+/M2):**
  `raft.server.log.purge.gap = 16` and/or
  `raft.server.log.segment.size.max = 64KB` in the SUT's production
  profile (or a `--profile snapshot-pressure` launcher flag) would make
  every churn cycle install and enable RATIS-2500-style
  repeated-install pressure without the workload-budget coupling this
  job navigates.
- **Report the ServerNotReady install-retry storm upstream** once a
  clean repro exists (kill a follower long enough for the leader to
  purge, restart it, watch the unthrottled retry loop during boot) —
  likely a missing backoff in `GrpcLogAppender`'s install path.
- **Evidence checker could dedupe to install *events*** (group by the
  `followerNextIndex = N but logStartIndex = M` pair) if counts ever
  feed assertions beyond nonzero.
- **`mixed-all` CI wiring**: once the SUT pressure profile exists,
  mixed-all could require evidence too.

## Environment notes (this execution sandbox, not the repo)

Same accommodations as Jobs 04/05 (loopback TLS-re-terminating egress
proxy), all uncommitted: local `ubuntu:24.04` shim with the proxy CA +
https apt sources; `RJ_EXTRA_CA_BUNDLE` + `RJ_DOCKER_BUILD_ARGS`
(Job 02's knobs) for the image build; control's `/root/.m2` seeded from
the host Maven cache; gnuplot side-loaded into control. One sandbox
wrinkle worth recording: the egress proxy's port changes across session
restarts, so the docker daemon and build args must re-read
`$HTTPS_PROXY` rather than hardcode it.
