# harness — the Clojure Jepsen harness for ratis-kv

Job 03 deliverable: the harness foundation. What exists here today is the
project skeleton, the Ratis `RaftClient` interop with the unit-tested
**outcome map** (the correctness-critical piece), and the node-lifecycle
(`db`) code written to the DESIGN §2.6 deployment contract. There are no
workloads, checkers or nemeses yet — Job 04 brings those and runs this
code against the real Docker topology.

## Layout

```
harness/
├── deps.edn                      # jepsen 0.3.13, ratis-client/grpc 3.2.2
├── src/ratis_jepsen/
│   ├── env_contract.clj          # DESIGN §2.6 constants, in one place
│   ├── outcome.clj               # THE OUTCOME MAP (DESIGN §2.4)
│   ├── client.clj                # jepsen Client via RaftClient interop
│   ├── db.clj                    # install/start/kill/wipe/logs (§2.6)
│   └── core.clj                  # jepsen.cli entry (test-map assembly)
└── test/ratis_jepsen/
    ├── outcome_test.clj          # one deftest per DESIGN §2.4 table row
    ├── client_test.clj           # op↔wire round-trips via the SUT codec
    ├── db_test.clj               # pure fns + startup regex + contract pin
    └── integration_test.clj      # 3 in-JVM SUT servers, 4 classifications
```

Namespace roles, briefly:

- **env-contract** — the only place deployment-contract values (nodes,
  raft port, install/storage/log paths, startup-line regex, the SUT's
  fixed group UUID) are stated. Everything else references it.
- **outcome** — `classify`: pure function from (op kind, wire reply or
  Throwable) to an `:ok`/`:fail`/`:info` verdict, implementing the
  DESIGN §2.4 table; `classify!` adds the loud-log side effect for
  flagged verdicts. Writes distinguish definite-not-applied (`:fail`)
  from ambiguous (`:info`); reads are never `:info`; anything
  unrecognized is pessimism plus a loud log.
- **client** — one `RaftClient` per Jepsen process, `noRetry`, ops
  `{:f :read}` / `{:f :write}` / `{:f :cas}` mapped 1:1 onto
  `GET`/`PUT`/`CAS`, every invocation under a 5 s harness-side timeout,
  op values as `jepsen.independent` tuples.
- **db** — jepsen `DB`/`LogFiles`/`Kill` to the §2.6 contract: tarball
  install from control, `start-stop-daemon` start with pidfile and
  startup-line await, `kill -9` by pidfile, storage wipe. Pure command
  construction is separated from `jepsen.control` calls and unit-tested
  without a cluster; container verification happens in Job 04.
- **core** — `jepsen.cli` single-test command assembling db + client
  with a noop nemesis and no generator yet.

## Running the tests

The integration test boots real ratis-kv servers in-process, so the SUT
jar must be in the local Maven repo first (from the repo root):

```
sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml -q install
```

Then, from `harness/`:

```
clojure -M:test          # all unit + integration tests
clojure -M:run test --help   # the jepsen CLI (usage only at this stage)
```

The unit tests need no cluster and no SUT processes. The integration
test starts three servers on fixed localhost ports 26631–26633 and takes
roughly half a minute, most of it leader election and teardown.

## What arrives in Job 04

Register workload (`:r`/`:w`/`:cas` over independent keys), generators,
knossos/elle checker wiring, the partition nemesis, and the first real
runs against the `env/` Docker topology — including verifying this `db`
implementation against real containers and the M0 exit-gate green/red
runs (seeded-bug catch). Until then, `clojure -M:run test` will attempt
to SSH to `n1..n5` and is not expected to succeed outside that topology.
