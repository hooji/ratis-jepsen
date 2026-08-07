# Job 11 — M4: durability faults via lazyfs

*Coordinator brief, 2026-08-06.*

**Read `jobs/README.md` first — binding.** Then `docs/PLAN.md` (M4),
`docs/DESIGN.md`, `docs/BACKLOG.md` item 4, `jobs/10-fuse-spike/10_report.md`
and `reviews/10-fuse-spike/10_report.md` (the spike this job is built
on). Base on current `main` (M3 complete; spike merged).

## Context

Every fault the harness ships so far is a *process* fault: kill, pause,
partition, membership, snapshot churn. None of them exercise the
storage layer's durability contract. The spike (Job 10) settled that
lazyfs works in our image and on hosted runners, and it settled
something more useful: a simple clear-cache fault on **one** node
recovers every acknowledged write, because Ratis syncs each append
before acknowledging. So this job's leverage is in the two shapes the
spike pointed at — **torn writes** and **cluster-wide simultaneous
faults** — plus one specific hypothesis worth testing directly
(deliverable 4).

This is the milestone the evaluation's JRaft comparison implied: their
harness ran green for years while the failure class it never touched
was exactly this one. Test the class we know is untested.

## Deliverables

1. **lazyfs in the environment** (`env/**`): build/install lazyfs at
   image-build time, pinned to the spike's commit
   (`045a0b3a1126725e693934e29d3ba15e08cc39ec`), with the spike's
   dependency pre-fetch accommodation. Keep it inert for existing
   scenarios — every current run must behave exactly as today when the
   feature is off (regression-check this explicitly).
2. **Mount lifecycle** (`harness/src/ratis_jepsen/db.clj`): when
   `--durability` is enabled, `/var/lib/ratis-kv` on each node is a
   lazyfs mount (configured cache size documented; the spike measured
   ~8 s / 1 GiB per mount — that is ×5 nodes of startup budget, so
   size it deliberately and report the real cost). Unmount cleanly on
   teardown/wipe; a failed mount fails the run loudly rather than
   silently running on the plain filesystem — **a durability run that
   wasn't actually on lazyfs is a broken test** (the evidence law,
   same as Jobs 07–09: prove the mount from the node, fail the run
   with a distinct error if unproven).
3. **Nemeses** (`--nemesis unsync-drop | unsync-drop-all | torn-write`,
   composable into `mixed-all` only where sensible):
   - `unsync-drop`: on a minority, discard un-synced page cache, then
     restart those nodes. Expectation: **green** — nothing
     acknowledged was un-synced.
   - `unsync-drop-all`: same fault on every node simultaneously, then
     restart all. Expectation: **green** on safety (no acknowledged
     write lost, no stale read); a temporary availability gap is
     legal, so the liveness checker needs nemesis-aware gating for
     this window.
   - `torn-write`: lazyfs's partial-write fault on one node's storage,
     then restart it. Expectation: the node either recovers cleanly or
     **refuses to start loudly** (Ratis's default `CorruptionPolicy` is
     EXCEPTION); the cluster keeps serving through its majority. A
     node that starts and serves *silently wrong* data, or a cluster
     that loses an acknowledged write, is a finding.
4. **The metadata-durability probe** (scripted, bounded; report-only
   is an acceptable outcome): Ratis persists `term`/`votedFor` in its
   raft-meta file. Determine, from source at 3.2.2 **and** by
   experiment, whether that file is synced before the node acts on the
   vote it records. Procedure: drive an election, apply the un-synced
   discard fault to a voter mid-election, restart it, and check
   whether its persisted term regresses. A regression that lets a node
   vote twice in one term is a safety defect of the highest class —
   preserve everything, triage carefully, and report. (Context: this
   is the precise class an external campaign found in the *other*
   library we evaluated; nobody has checked Ratis for it publicly.)
5. **Expectations table in the report** — one row per scenario:
   in-model or out-of-model, what a pass looks like, what a finding
   would look like. Reviewers and future readers must not have to
   infer intent from code. Note BACKLOG item 4: unequal durable-state
   loss across nodes is **out-of-model** (it destroys committed data
   that exists nowhere else); scenarios must not accidentally do that
   and then convict Ratis for it.
6. **Runs + ledger** (`docs/RUNS.md` append): each nemesis green (or a
   preserved finding), the register workload under `unsync-drop` and
   `unsync-drop-all`, one counter-workload run under `unsync-drop`
   (durability × exactly-once), and the mount-evidence negative arm.
   Report the startup-budget cost per run.
7. **CI**: add the durability scenarios as dispatchable tokens
   (minimal, itemized diff). Note the runner cost — if a durability
   sweep is materially slower, say so; the coordinator decides
   defaults.
8. **`jobs/11-durability-faults/11_report.md`** per `jobs/README.md`.

## File ownership

`env/**`, `harness/**`, `docs/RUNS.md` (append),
`.github/workflows/jepsen.yml` (scenario tokens, itemized),
`jobs/11-durability-faults/11_report.md`. No `sut/**` changes — if the
SUT needs one, stop and report. **Parallel-safe with: none.**

## Acceptance criteria (command + output excerpt each)

1. Suites green; existing scenarios byte-for-byte unaffected when
   `--durability` is off (show a regression run).
2. Mount evidence proven per node on a durability run; negative arm
   demonstrated (unmounted ⇒ distinct error, run fails).
3. Each of the three nemeses run with its stated expectation met, or a
   preserved finding with triage.
4. Metadata probe: source determination + experiment result, stated
   plainly either way.
5. Counter workload under `unsync-drop`: exactly-once still holds.
6. Startup/runtime cost reported; established reporting (analysis
   times, `:info` sanity, ownership, headers, report).

## Non-goals

Version matrix (M5), upstream filing (owner-gated, later), SUT
changes, performance tuning of lazyfs beyond making runs bounded.

## Note

The expectations table is the deliverable that makes this milestone
credible rather than alarming. A durability run that goes red is only
meaningful if we said in advance what red would mean — and only
publishable if the fault we injected was inside the model Ratis
promises to survive.
