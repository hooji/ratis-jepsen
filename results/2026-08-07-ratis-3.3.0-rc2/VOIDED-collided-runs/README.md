# VOIDED — two runs that collided on one cluster (kept for the record)

**Neither run in this directory is a reference result. Neither verdict
means anything about Ratis.** They are published because this
repository does not silently discard runs — and because *how* they
went wrong is itself instructive.

## What happened

Both runs are `register + partition --reads mixed --ratis-version
3.3.0` (RC2). The Job 14 worker's run orchestration made a mistake: a
background wrapper script that the worker believed had been killed by
a tooling timeout was in fact still alive, and launched this scenario
at 18:35:36 UTC (run A). The worker, acting on the wrong belief,
manually launched the same scenario at 18:37:05 UTC (run B). **Two
Jepsen harnesses then drove the same five-node cluster
simultaneously**:

- 18:35:45 — run A's op phase begins.
- 18:37:05–18:37:17 — run B starts: Jepsen's standard pre-run
  teardown+setup **kills every node and wipes its storage** — in the
  middle of run A's op phase — then boots a fresh cluster.
- 18:37:17–18:40:46 — both harnesses run ops concurrently against the
  same nodes. Both register workloads use the same wire keys
  (`0`–`4`), so each run's writes are invisible foreign mutations to
  the other's checker; both nemeses also partition/heal the same
  network independently.
- 18:40:46 — run A completes; 18:42:03 — run B completes.

## The outcomes, and why both are void

| Run | Store timestamp | Exit | Verdict |
|---|---|---|---|
| [`run-A-183536/`](run-A-183536/) | `20260807T183536.050Z` | 0 | `:valid? true` — **uninterpretable, not evidence** |
| [`run-B-183705/`](run-B-183705/) | `20260807T183705.605Z` | 2 | `:valid? :unknown` — knossos returned `:unknown` on all five keys (832 / 474 / **194** ok/fail/info; the info explosion is the trampling) |

Run A's "green" deserves the explicit caution: its cluster was wiped
and rebooted under it, and a second workload wrote its keys for three
and a half minutes, yet its per-key histories happened to stay
formally consistent. A green verdict is only meaningful **under the
run's fault model**; out-of-model interference voids the run whether
the checker convicts or not. That is why these runs were disqualified
*on the collision facts* — before either verdict was known, and
independent of what the verdicts turned out to be.

## What replaced them

The scenario was re-run **once**, alone, on the quiesced topology:
[`../partition-reads-mixed/`](../partition-reads-mixed/) — that run is
the reference row in the version README. The full account (including
this incident) is in `jobs/14-reference-runs/14_report.md`.

Kept here per run: `results.edn` and `jepsen.log.gz` (enough to audit
the timeline above). The full stores exist in the worker environment's
`store/` but were not committed.
