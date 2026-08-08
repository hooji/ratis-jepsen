# harness — the Clojure Jepsen harness for ratis-kv

The test harness: workloads, fault schedules, checkers, node lifecycle,
and the Ratis `RaftClient` interop with its unit-tested **outcome map**
(the correctness-critical piece). It runs on the `control` container and
drives `n1..n7` over SSH; `env/run.sh test` is the supported entry point
and passes every flag through to the CLI here.

## Layout

```
harness/
├── deps.edn                      # jepsen 0.3.13, ratis-client/grpc/metrics-default
├── src/ratis_jepsen/
│   ├── env_contract.clj          # DESIGN §2.6 constants, in one place
│   ├── outcome.clj               # THE OUTCOME MAP (DESIGN §2.4)
│   ├── client.clj                # jepsen Client via RaftClient interop
│   ├── db.clj                    # install/start/kill/pause/wipe/logs, lazyfs mounts
│   ├── nemesis.clj               # every fault schedule
│   ├── checker.clj               # liveness, counter, and the evidence checkers
│   ├── workload/register.clj     # r/w/cas over independent keys
│   ├── workload/counter.clj      # exactly-once ADD workload
│   └── core.clj                  # jepsen.cli entry (CLI + test-map assembly)
├── probe/ratis_jepsen/probe/
│   └── lifecycle.clj             # in-JVM library probe (BACKLOG 7/8), Job 12
├── scripts/metadata-probe.sh     # term/votedFor durability probe (Job 11)
└── test/ratis_jepsen/
    ├── outcome_test.clj          # one deftest per DESIGN §2.4 table row
    ├── client_test.clj           # op↔wire round-trips via the SUT codec
    ├── db_test.clj               # pure fns + startup regex + contract pin
    ├── nemesis_test.clj          # schedule/vocabulary/targeting properties
    ├── checker_test.clj          # liveness, counter and evidence verdicts
    ├── core_test.clj             # CLI parsing, defaults, test-map assembly
    └── integration_test.clj      # 3 in-JVM SUT servers, 4 classifications
```

Namespace roles, briefly:

- **env-contract** — the only place deployment-contract values (nodes,
  raft port, install/storage/log paths, startup-line regex, the SUT's
  fixed group UUID) are stated. Everything else references it.
- **outcome** — `classify`: pure function from (op kind, wire reply or
  Throwable) to an `:ok`/`:fail`/`:info` verdict; `classify!` adds the
  loud-log side effect for flagged verdicts. Writes distinguish
  definite-not-applied (`:fail`) from ambiguous (`:info`); reads are
  never `:info`; anything unrecognized is pessimism plus a loud log.
  Its docstring table is the live version of DESIGN §2.4.
- **client** — one `RaftClient` per Jepsen process with bounded
  same-callId retries (DESIGN §2.4's 2026-08-05 amendment), ops
  `{:f :read|:write|:cas|:add}` mapped 1:1 onto `GET`/`PUT`/`CAS`/`ADD`,
  every invocation under a harness-side timeout, op values as
  `jepsen.independent` tuples. Reads go to the leader or, under
  `--reads follower|mixed`, to a named peer.
- **db** — jepsen `DB`/`LogFiles`/`Kill`/`Pause` to the §2.6 contract:
  tarball install from control, `start-stop-daemon` start with pidfile
  and startup-line await, `kill -9` by pidfile, SIGSTOP/SIGCONT, storage
  wipe, plus the leader census the nemeses use for targeting, the
  `--join` start mode for pool nodes, the lazyfs mount setup for
  `--durability`, and per-version install for mixed-version runs.
- **nemesis** — every `--nemesis` kind as a self-contained cycle
  (`none`, `partition`, `crash`, `pause`, `mixed`, `snapshot-churn`,
  `transfer`, `membership`, `membership-snapshot-churn`,
  `listener-probe`, `quorum-pause`, `mixed-all`, `unsync-drop`,
  `unsync-drop-all`, `torn-write`, `rolling-upgrade`), with one
  fault→heal vocabulary map that the liveness checker's nemesis-aware
  gating is derived from.
- **checker** — the liveness checker (healthy-majority no-progress
  windows), the counter bounds checker, and the evidence checkers that
  fail a run whose fault never actually happened: install-snapshot
  events, committed conf transitions, joiner installs, durability fault
  acknowledgements, retry counts, applied rolls.
- **workload/register**, **workload/counter** — generators plus the
  composed checker for each workload.
- **core** — the `jepsen.cli` entry: option parsing and validation,
  per-workload defaults, the `--ratis-version` / `--mixed-version`
  client-skew check, and test-map assembly.

## Running the tests

**In the shipped environment (needs only Docker)** — from the repo
root, with the topology up:

```
env/run.sh up          # once
env/run.sh selftest    # SUT jar install + all unit and integration tests
```

`selftest` installs the SUT jar into control's Maven repo volume and
runs `clojure -M:test` there; exit 0 means every test passed. CI runs
the same suite on every workflow dispatch (the `harness-tests` job).

**Directly on a host** that has JDK 21 and the Clojure CLI installed
(neither is needed for the Docker path above): the integration test
boots real ratis-kv servers in-process, so the SUT jar must be in the
local Maven repo first (from the repo root):

```
sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml -q install
```

Then, from `harness/` (the test runner resolves `test/` relative to
the working directory):

```
clojure -M:test          # all unit + integration tests
clojure -M:run test --help   # every CLI option, with its default
```

The suite's entry point is `test/ratis_jepsen/test_runner.clj` (plain
`clojure.test`; namespaces discovered from the directory, so a new
test file is picked up automatically — Job 17 replaced the cognitect
test-runner *git* dependency, which could not resolve inside the
gitless control container). The unit tests need no cluster and no SUT
processes. The integration test starts three servers on fixed
localhost ports 26631–26633 and takes roughly half a minute, most of
it leader election and teardown.

Running `clojure -M:run test` directly attempts to SSH to `n1..n5`; use
`env/run.sh test` instead, which runs it inside `control` against the
Docker topology and matches the harness's own `ratis-client` to the
version under test.

## The probes

Two report-only probes live here; neither is part of a normal run.

- `probe/…/lifecycle.clj` (`env/run.sh probe --ratis-version V`) — boots
  in-JVM `RaftServer`s on a deliberately *naive* `BaseStateMachine`
  subclass that does not manage the lifecycle, and drives a live
  install-snapshot at it. This is what establishes BACKLOG 7 and 8 at a
  given Ratis version, independent of our own SUT (which manages the
  lifecycle and therefore masks item 7).
- `scripts/metadata-probe.sh` — on a running durability topology,
  compares each node's `raft-meta` through the lazyfs mount against the
  backing copy across forced elections, and checks the recovered term
  after kill + cache-drop + restart. Its decision rules still need the
  hardening described in BACKLOG 11 before the experiment is quoted
  externally; until then its result is stated as "source-proven;
  probe-consistent".
