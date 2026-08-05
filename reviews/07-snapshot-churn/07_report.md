# Review 07 report — Job 07: M2 part 1: snapshot churn, leadership transfer, follower reads

Worker PR: #15 (head `cee5c49`, base `ab98b5c`). Reviewed in a detached
worktree at the PR head; all runs and source checks below are mine.
Docker 29.3.1, 4 cores / 15 GB; sandbox accommodations as before (slim
CA bundle through the env's own knobs — no JVM-truststore seeding
needed since Job 06's fix — plus git installed in control for
`-M:test`).

## Verdict: MERGE

## Justification

Both of this job's load-bearing deviations survive adversarial
verification at the source level, which was the review's center of
gravity. (1) The `LeaderSteppingDownException → definite write :fail`
row — the same *shape* of claim whose NLE variant caused Review 05's
false-red — is **proven sound at ratis 3.2.2**: one construction site
in the entire codebase, reachable only pre-append, with the
appended-entry step-down path demonstrably using NotLeaderException
instead; empirically, 51+ LSDE completions across my transfer-heavy
runs graded definite `:fail` with zero convictions and zero `:info`
flood. (2) The churn-mechanism redesign's three claims all verify
verbatim in the Ratis sources, and the shipped cycle demonstrably
reaches install-snapshot on my runs while the evidence checker's
negative arm fails a tested-nothing run with the distinct error — on a
real cluster, not just fixtures. The full matrix reproduces: five
greens (including follower reads under partition and mixed-all), the
seeded-red convicts through full churn, both probes pass — including
killing the *sitting leader* mid-churn with my own hand, outside the
nemesis's model, with the run staying green and the resulting 8
ambiguous ops correctly graded and clustered at my kill. Analyses all
sub-second; ownership sharp (workflow diff = the one granted line).
The no-backoff InstallSnapshot observation reproduces at 4× the
worker's magnitude on my slower environment, upgrading its
upstream-report priority. Findings are non-blocking.

## Emphasis 1 — the LSDE row, settled from source (was
potentially-BLOCKING)

**Enumeration**: swept the 3.2.2 sources of ratis-server,
ratis-client, ratis-common and ratis-grpc for constructions —
**exactly one**: `RaftServerImpl.java:791`, inside
`checkLeaderState(request, entry)`:

```java
if (!request.isReadOnly() && isSteppingDown()) {
  final LeaderSteppingDownException lsde = new LeaderSteppingDownException(...);
  final RaftClientReply reply = newExceptionReply(request, lsde);
  return RetryCacheImpl.failWithReply(reply, entry);
}
```

**Pre-append proof**: every write path consults `checkLeaderState`
and returns its non-null reply *before any append*:

- `appendTransaction` (line 847): the call sits inside
  `synchronized (this)` and early-returns **before**
  `state.appendLog(context)` (line ~861) — an LSDE reply and an
  appended entry are mutually exclusive by control flow;
- `writeAsyncImpl` (line 992): `checkLeaderState` early-return
  precedes `startTransaction` and everything after;
- remaining callers are watch/read/stream/admin paths — and reads
  cannot receive LSDE at all (`!request.isReadOnly()` guard), which
  also validates the worker's loud-pessimism read row.

**The contrast that made NLE ambiguous does not exist for LSDE**:
appended-but-uncommitted pending requests at step-down are completed
via `LeaderStateImpl.stop()` →
`pendingRequests.sendNotLeaderResponses(server.generateNotLeaderException(), …)`
(LeaderStateImpl.java:435-438) — NotLeaderException, never LSDE.
`TransferLeadership.java` completes only its own transfer future
(success or `TransferLeadershipException`), never client entries.

**Empirical arm**: my transfer run (14/14 successful handoffs) and
churn runs produced 47 + 4 + more LSDE completions — **all
`:type :fail`, zero `:info`**, every run `:valid? true`. If LSDE'd
writes could commit, knossos would convict exactly as it did in the
NLE episode; it never does. The row is sound both ways.

## Emphasis 2 — churn mechanism, source-verified and demonstrated

The redesign's three claims, all confirmed in ratis-3.2.2 sources:

1. **Purge drops closed segments only**:
   `SegmentedRaftLogCache.purge(index)` (line 639) delegates to
   `closedSegments.purge(index)` — the open segment is structurally
   untouchable.
2. **Segments roll at size or term change**:
   `SegmentedRaftLog.appendEntryImpl` (lines 405-428) sets
   `rollOpenSegment = true` iff `isSegmentFull(...)` or
   `last.getTerm() != entry.getTerm()`.
3. **`purge.gap` floors purge frequency**: `RaftLogBase.purge`
   (lines 318-329): `if (adjustedIndex - lastPurge < purgeGap) return
   completedFuture(lastPurge)`.

So the brief's kill+snapshot+restart cycle alone cannot reach
install-snapshot (8 MB segments never fill here; no term change ⇒
nothing closes ⇒ nothing purges) — the in-cycle transfer is necessary,
not gold-plating. Demonstrated on my runs: churn #1 produced a genuine
install pair —

```
n3@…->n4-GrpcLogAppender: followerNextIndex = 1081 but logStartIndex = 1099,
  send snapshot SingleFileSnapshotInfo(t:6, i:1138) … to follower
n4@…: receive installSnapshot: n3->n4#0-t6,chunk:…
```

— landing just past the ~1024 purge-gap milestone as designed; churn
#2 produced two events (evidence total 4). **Negative arm on a real
cluster**: my tiny-rate probe (below) and the worker's preserved
transfer-less shakedown both fail with
`:error :no-install-snapshot-evidence`.

## The matrix (my runs; wall / knossos analysis)

Reference commands per the ledger (churn runs add
`--rate 1.4 --ops-per-key 800`):

| Run | Exit | Wall | Analysis | ok / fail / info | Verdict |
|---|---|---|---|---|---|
| snapshot-churn #1 | 0 | 349 s | 0.71 s | 1494 / 580 / 0 | valid; evidence 2; 47 LSDE all `:fail` |
| snapshot-churn #2 **+ leader-kill probe** | 0 | 324 s | 0.76 s | 1461 / 610 / 8 | valid; evidence 4 |
| transfer | 0 | 320 s | 0.41 s | 1115 / 385 / 0 | valid; 14/14 transfers `:success? true` |
| partition + `--reads mixed` | 0 | 321 s | 0.65 s | 1011 / 482 / 7 | valid; 256 follower-targeted reads (`:read-via` n1:25 n2:36 n3:71 n4:59 n5:65), 185 `:ok`, all judged fully linearizable |
| mixed-all | 0 | 335 s | 0.51 s | — | valid; drew 2 churn / 2 crash / 1 pause / 4 partition / 6 transfer segments; evidence reported, not required |
| snapshot-churn + seed-bug | **1** | 317 s | 0.65 s | — | `:failures [0 1 2 3 4]` (`can't read 0 from register 4`); **2 install events landed during the seeded run** — churn does not launder the detector |
| probe: churn at `--rate 0.3`, 120 s | **1** | 136 s | 0.18 s | — | **`:valid? false, :error :no-install-snapshot-evidence`** with the full explanatory note — a tested-nothing churn run cannot pass |

Unit suite: `Ran 61 tests containing 652 assertions. 0 failures`
(matches the report; includes the evidence-checker fixtures for
zero/nonzero and the churn/transfer segment shapes).

## Probes

1. **Leader kill mid-churn** (the harder review-brief option): at
   t+100 s of churn #2 I killed the *sitting leader* (n5) with a raw
   in-container `kill -9` — a fault outside the nemesis's model. The
   run stayed green end to end; the next `:churn-restart`'s
   start-everything heal revived n5 (4 boots in its log); and the
   run's only 8 `:info` ops cluster in a 1.7 s burst at t=86.7–88.4 s
   — exactly my kill instant in history time (probe wall-offset minus
   jepsen setup). The outcome map graded an unplanned leader death
   correctly, and my nemesis-window analyzer flagging them "outside
   fault windows" is the analyzer being right about a fault only I
   knew about.
2. **Adversarially tiny `--rate`**: 0.3 ops/s/worker for 120 s keeps
   committed indexes far below the purge gap ⇒ zero installs ⇒ the
   evidence checker **fails the run** as tested-nothing (table above)
   — the Review-01 lesson enforced on a live cluster.

## Emphasis 4 — the no-backoff InstallSnapshot observation, amplified

Reproduced on churn #1: the rebooting follower's log carries ~1,623
distinct rejected-request traces
(`ServerNotReadyException: … is not in [STARTING, RUNNING]: current
state is CLOSED`, mostly wrapped in `CompletionException`) across its
reboot windows — **4× the worker's ~400**, on my slower box with
longer boot windows. That is their own severity prediction ("on a
slow-booting follower this would spam thousands") observed literally.
Assessment: agree it converges cleanly (every run green; installs
complete; not RATIS-2500's infinite loop — that loop never terminates,
this one ends the moment the follower's division opens) — but the
retry loop is demonstrably unthrottled and scales with boot latency,
which is RATIS-2500-*adjacent* in mechanism (leader-side retry
without damping against a not-ready follower). **Recommend the
coordinator queue an upstream report** once a minimal repro exists
(kill follower → purge past it → restart under load; the harness now
produces this on demand), with both data points (400/15.6 s and
~1.6 k on a slow boot) as the scaling evidence.

## Emphasis 5 — transfer-target selection

Code-audited: the churn transfer excludes the sitting leader using
the log census as primary and the admin client's `getLeaderId` as
fallback (the docstring documents why: the admin client's hint is
null before its first request), and excludes the killed follower;
candidates never empty (falls back to non-killed nodes). A stale
census can at worst pick the actual leader ⇒ transfer-to-self ⇒ a
no-op term-wise ⇒ that cycle degrades to log catch-up — and
degradation past zero installs is exactly what the required evidence
checker converts into a run failure. Staleness is thus contained by
construction: it can weaken cycles, never falsify results. The
standalone `transfer` nemesis deliberately targets any voter
(self-transfer legal and recorded); `TransferLeadershipException`
timeouts are caught and recorded as legal outcomes (14/14 succeeded
in my run, so the tolerance path is exercised only by the worker's
preserved shakedowns — code-audited, low risk).

## Emphasis 6 — budgets

`gen/limit ops-per-key` cap unchanged and still enforced
(`--ops-per-key 800` × 5 keys bounds each history; my runs completed
1.5–2.1 k ops before the time limit); `--rate` only stretches
stagger; all seven analyses 0.18–0.76 s — the retry-era clean
histories keep knossos far from the cliff even at the larger budget.

## Criterion 7 — ownership, headers, report

13 files: `harness/**` (8 src/test), `docs/RUNS.md` (append-only),
`.github/workflows/jepsen.yml` (**exactly the one granted
scenarios-default line** — verified by diff), and the job report.
Headers intact on changed files (no new files needing headers — all
new code lives in existing namespaces; test additions in existing
test files). No artifacts in the diff; worktree clean after runs;
`run.sh down` → 0 containers. Worker report complete per
`jobs/README.md`, and every number of theirs I recomputed matched
(suite counts, evidence totals, store IDs, LSDE-OOM history,
mixed-all draw shapes differ per run as expected).

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking (observation, feeds backlog) | upstream (ratis) | The no-backoff InstallSnapshot retry storm: reproduced at ~1.6 k rejected attempts on a slow follower boot vs the worker's ~400 — unthrottled leader-side retries scaling with boot latency. Converges; not a harness or SUT defect; upstream-report-worthy with the harness as the repro (recommended above). |
| 2 | non-blocking | `harness/src/ratis_jepsen/checker.clj:283-292` | `evidence-verdict`'s `required?` gating means a `mixed-all` run that *happens* to draw many churn segments still never requires evidence. Correct per the documented design (its churn share sits below the purge gap), but if M3+ ever raises mixed-all's budget, revisit whether it should inherit the requirement. |
| 3 | non-blocking | `harness/src/ratis_jepsen/nemesis.clj:186-199` | churn-kill falls back to `nodes` when the census fails or returns everything — i.e. it may kill the leader that cycle (documented: "still churns, just without the held-back-follower shape"). My leader-kill probe demonstrates the harness handles exactly this shape; no action needed, noting the behavior is intentional. |
| 4 | non-blocking | `docs/RUNS.md` | The ledger's churn commands embed `--rate 1.4 --ops-per-key 800` as the reference; the CI workflow's default sweep runs churn at defaults (rate 10, 300/key), where the op budget exhausts early and evidence depends on raw op count crossing the purge gap (~1 k write-path ops of ~1.5 k total — marginal). A CI churn leg could intermittently fail as tested-nothing. Suggest the coordinator either bake the churn parameters into the workflow invocation or accept occasional evidence-failures as honest signal. |

## Suggestions (non-blocking)

- Fast-track the upstream InstallSnapshot-backoff report (Finding 1);
  the two-environment scaling data is a good opening exhibit, and the
  tiny-rate/leader-kill probe machinery doubles as the repro script.
- Finding 4's CI-parameters question is worth settling before the
  first `main` sweep with the new default list (one workflow line
  either way — coordinator-owned).
- The `:read-via` annotation is quietly excellent diagnostics; a
  timeline/perf overlay of read targets vs partitions would make
  follower-read regressions visually obvious (M3+ polish).
