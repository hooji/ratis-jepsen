# ratis-jepsen

[![jepsen](https://github.com/hooji/ratis-jepsen/actions/workflows/jepsen.yml/badge.svg)](https://github.com/hooji/ratis-jepsen/actions/workflows/jepsen.yml)

A from-scratch [Jepsen](https://github.com/jepsen-io/jepsen) test harness
for [Apache Ratis](https://ratis.apache.org/), the Java Raft library:
black-box correctness testing of a real replicated 5-node deployment under
fault injection, aimed at the defect classes Ratis's own tracker history
shows are real — snapshot install, restart/liveness, membership change,
read-index edges.

## Status

**M0 (walking skeleton) complete.** A 5-voter Ratis KV cluster in Docker,
a linearizable register workload (read/write/CAS) driven through the real
`RaftClient`, a partition nemesis, and per-key linearizability checking:

- **Green on healthy clusters** — the reference run passes the checker
  under a cycling random-halves network partition.
- **Red when the SUT lies** — the harness demonstrably catches a seeded
  stale-reads bug as a concrete linearizability violation (the "test of
  the test").

Both reference runs, with commands and verdict excerpts, are recorded in
[`docs/RUNS.md`](docs/RUNS.md). M1 — nemesis breadth (crash-restart,
pause), liveness checking, and CI — is in progress.

## Quickstart

Requires Docker with the compose plugin. From the repository root:

```sh
env/run.sh up                                    # image + control & n1..n7, await ssh
env/run.sh test --nemesis partition --time-limit 300
env/run.sh down                                  # tear it all back down
```

`test` builds the SUT tarball on first use, installs it on the five voter
nodes, runs the workload, and exits with the checker's verdict (0 = pass);
results land in `store/<test-name>/<timestamp>/`. `--nemesis none` is the
fault-free baseline; `--seed-bug stale-reads` plants the deliberate SUT bug
a correct harness must catch. Topology details and knobs (proxy CAs,
timeouts) are in [`env/README.md`](env/README.md).

## CI

The [`jepsen` workflow](.github/workflows/jepsen.yml) is **manual-dispatch
only** — no schedule, deliberately: the intent is to offer this harness to
the Apache Ratis project, whose maintainers would then own the test
cadence. A dispatch takes a comma-separated `scenarios` list (default
`none,partition`) plus a `time-limit`, builds the SUT tarball once, fans
out one runner per scenario, and always uploads each run's compressed
`store/` as an artifact (7-day retention). Every sweep also runs a
**red-gate** job — a short seeded-bug run that passes only if the harness
*fails* it with a checker conviction — so each sweep re-proves the harness
can still catch bugs before its greens are believed.

## Pointers

| Path | What |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | purpose, decisions, milestones, open questions |
| [`docs/DESIGN.md`](docs/DESIGN.md) | the M0 design: SUT, harness, env, CI shape |
| [`docs/PROCESS.md`](docs/PROCESS.md) | how this repo is built (coordinated jobs + adversarial reviews) |
| [`docs/RUNS.md`](docs/RUNS.md) | reference-run ledger (green and seeded red) |
| `sut/ratis-kv/` | the system under test: a KV server embedding `RaftServer` |
| `harness/` | the Clojure Jepsen harness (client, nemeses, checkers) |
| `env/` | Docker topology (control + 7-node pool) and entry scripts |

## License

Apache-2.0. Source files carry the license header. The harness is written
from scratch; prior art (notably sofa-jraft-jepsen) was studied for shape
only, not copied.
