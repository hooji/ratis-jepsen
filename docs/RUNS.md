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
