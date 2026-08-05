# Run ledger

Reference runs, newest last. Summaries only — `store/` artifacts stay out
of git; each entry names the command that reproduces it.

## 2026-08-04 — M0 reference GREEN (Job 04 exit gate)

- **Command**: `env/run.sh up && env/run.sh test --nemesis partition --time-limit 300`
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21
- **Duration**: 313 s wall (≈25 s node install/boot, ops until the
  5×300-op budget exhausted, nemesis cycling to the time limit, 0.4 s
  knossos analysis)
- **Verdict**: `:valid? true`, exit 0 — "Everything looks good!"
- **Store**: `ratis-kv-register-partition/20260804T172534.329Z`

1500 ops over 5 independent keys checked in parallel (2 workers/key,
~10 ops/s each): 1072 `:ok`, 428 `:fail`, 0 `:info`. Every per-key
knossos cas-register analysis passed under a random-halves partition
cycling 15 s on / 15 s off. The fails are honest definite outcomes: CAS
precondition misses by design, plus a handful of `:not-leader-or-not-ready`
and `:read-index` transients around elections. In this run every partition
window that overlapped the op phase kept the leader on the majority side,
so no op ended ambiguous; two sibling runs of the same command (archived
`…T170652Z`, `…T171457Z`) caught leader-minority windows and show the
`:info` behavior — 60 and 71 write-path `:info` ops, 96–97 % inside or
within 5 s of a partition window, none in calm phases (the outliers sit
5.5–6.5 s after a heal, on still-recovering connections).

## 2026-08-04 — M0 reference RED (seeded bug caught)

- **Command**: `env/run.sh test --nemesis partition --time-limit 300 --seed-bug stale-reads`
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21
- **Duration**: 313 s wall (0.5 s analysis)
- **Verdict**: `:valid? false`, exit 1 — "Analysis invalid!", failures on
  **all five keys** (`:failures [0 1 2 3 4]`)
- **Store**: `ratis-kv-register-partition-seedbug-stale-reads/20260804T173140.098Z`

Same workload with every node started with `--seed-bug stale-reads`
(linearizable reads answered from a state copy that lags commits by
~500 ms; the nodes' logs carry the
`*** SEEDED BUG ACTIVE: stale-reads ***` banner). The checker convicts
within the first seconds of history. Key 0's violating pair, verbatim
from the analysis — one process wrote 2, then read the older 1:

```
{:op {:process 0, :type :ok, :f :write, :value 2, :index 228, :time 4337901282}}
{:op {:process 0, :type :ok, :f :read,  :value 1, :index 233, :time 4362177750},
 :model #knossos.model.Inconsistent{:msg "can't read 1 from register 2"}}
```

This is the M0 "test of the test": the harness demonstrably reports red
when the SUT lies.

## 2026-08-05 — M1 reference GREEN: crash ×2 (Job 05)

- **Command**: `env/run.sh up && env/run.sh test --nemesis crash --time-limit 300` (×2)
- **Versions**: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21
- **Duration / analysis**: run 1: 328 s wall, 5.4 s analysis; run 2: 322 s wall, 2.1 s analysis
- **Verdict**: both `:valid? true`, exit 0 — "Everything looks good!"
- **Stores**: `ratis-kv-register-crash/20260805T044717.351Z`, `…/20260805T045245.825Z`

Ten crash cycles per run (calm 20 s → `kill -9` a random 1–2 of 5, leader
forced in with p=0.5 → dead 10 s → restart all): killed nodes report
`:started` on restart, the rest `:already-running`; the SUT recovers via
`StartupOption.RECOVER` and rejoins (re-election transitions in every
node log). Stats: run 1 = 1057 `:ok` / 408 `:fail` / 35 `:info`; run 2 =
1036 / 439 / 25. Outcome-mapping sanity: **all** 35 and 25 write-path
`:info` completions fall **inside** a kill window ([`:crash` invocation,
`:restart` completion]) — none adjacent, none in calm phases; reads have
zero `:info` (as the outcome map requires). The new liveness checker is
composed in and `:valid? true`, its calm regions tracking the fault
windows from the history's nemesis events (last cycle's kill at t≈295
stays an open window to history end — the time limit cuts before its
restart, and teardown cleans up).

## 2026-08-05 — M1 reference GREEN: pause (Job 05)

- **Command**: `env/run.sh test --nemesis pause --time-limit 300`
- **Duration / analysis**: 322 s wall, 0.7 s analysis
- **Verdict**: `:valid? true`, exit 0
- **Store**: `ratis-kv-register-pause/20260805T045807.172Z`

Ten SIGSTOP/SIGCONT cycles (running 25 s / stopped 5 s, random 1–2 of 5,
unbiased). 1098 `:ok` / 402 `:fail` / **0 `:info`** — every outcome
definite: pauses hit followers for the first eight cycles (boot leader n4
first entered the paused set near t≈250); the final leader-pause produced
a term-2 election (n5, t≈281) and the run's 10 `:not-leader-or-not-ready`
fails; the other 392 fails are designed CAS precondition misses.

## 2026-08-05 — M1 reference GREEN: mixed (Job 05)

- **Command**: `env/run.sh test --nemesis mixed --time-limit 300`
- **Duration / analysis**: 326 s wall, 1.7 s analysis
- **Verdict**: `:valid? true`, exit 0
- **Store**: `ratis-kv-register-mixed/20260805T050331.288Z`

Ten whole fault segments drawn uniformly at random — this run drew
5 partition / 4 pause / 1 crash (uniform draws, small n; the mix varies
per run). Faults never overlap by construction (each segment heals before
the next begins). 1068 `:ok` / 432 `:fail` / 0 `:info`; liveness
`:valid? true`.

## 2026-08-05 — M1 reference RED: seeded bug caught amid restarts (Job 05)

- **Command**: `env/run.sh test --nemesis crash --time-limit 300 --seed-bug stale-reads`
- **Duration / analysis**: 333 s wall, 0.7 s analysis
- **Verdict**: `:valid? false`, exit 1 — "Analysis invalid!", failures on
  **all five keys** (`:failures [0 1 2 3 4]`)
- **Store**: `ratis-kv-register-crash-seedbug-stale-reads/20260805T050901.890Z`

The M0 red-run lever under the M1 crash nemesis: every node runs
`--seed-bug stale-reads` (reads answered from a ~500 ms-lagging shadow
map), ten kill cycles land during the run (the last one's restart falls
past the time limit, as in the green runs), and restarted nodes
rejoin with the bug still active — the
`*** SEEDED BUG ACTIVE: stale-reads ***` banner reappears in every node's
log after every restart (the flag is plumbed through the same `db.clj`
start path the crash nemesis uses). The checker convicts anyway. Key 0's
violating pair, verbatim: process 0 wrote 2, then read the stale 3:

```
{:op {:process 0, :type :ok, :f :write, :value 2, :index 105, :time 4396595457}}
{:op {:process 0, :type :ok, :f :read,  :value 3, :index 116, :time 4527382074},
 :model #knossos.model.Inconsistent{:msg "can't read 3 from register 2"}}
```

(1022 `:ok` / 427 `:fail` / 51 `:info` — stale reads still *acknowledge*,
so the liveness checker rightly stays `:valid? true`; linearizability is
what convicts.)
