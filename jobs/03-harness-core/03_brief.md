# Job 03 — harness core: Clojure skeleton, client + outcome map, db lifecycle

*Coordinator brief, 2026-08-04.*

**Before anything else, read `jobs/README.md` — it is binding.** Then
`docs/PLAN.md`, `docs/DESIGN.md` (§2 is this job's spec, §2.6 is the
deployment contract), and this brief. Base your branch on **current**
`main` (Job 01 is merged; DESIGN §2.6 is new).

## Context

Job 01 (merged) built the SUT: `sut/ratis-kv` — wire protocol
`PUT/CAS/GET` ↔ `OK/VAL/ABSENT/MISMATCH/ERR` (see
`jobs/01-sut/01_brief.md`), Maven coordinates
`ratis-jepsen:ratis-kv:0.1.0-SNAPSHOT`. Job 02 builds the Docker
topology **in parallel with you** — you never touch `env/**`, and your
db code is written *to the DESIGN §2.6 contract*, verified against real
containers only in Job 04. This job delivers the harness's foundation:
project skeleton, the Ratis client interop with the **unit-tested
outcome map** (the correctness-critical piece), and the node-lifecycle
code.

## Deliverables

1. **`harness/deps.edn`** — Clojure project. Deps: `jepsen/jepsen`
   (latest 0.3.x you can resolve — record the exact version in your
   report), `org.apache.ratis/ratis-client` and `ratis-grpc` at
   **3.2.2**, and (test alias only) `ratis-jepsen/ratis-kv
   {:mvn/version "0.1.0-SNAPSHOT"}` for the in-JVM integration test.
   Aliases: `:run` (main entry), `:test` (test runner). Getting the SUT
   jar visible requires `sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml -q
   install` first — that is an acceptance-criteria step, not a code
   change to `sut/**`.
2. **Namespaces** (per DESIGN §2.1):
   - `ratis-jepsen.env-contract` — the DESIGN §2.6 constants (nodes,
     port, paths, startup-line regex) in **one place**; everything else
     references it.
   - `ratis-jepsen.client` — jepsen `client/Client` implementation:
     `open!` builds one `RaftClient` per Jepsen process
     (`RaftClient.newBuilder().setProperties(new RaftProperties())
     .setRaftGroup(...).setRetryPolicy(RetryPolicies.noRetry())
     .build()`; the group is the SUT's fixed UUID + the node list from
     env-contract — the UUID is a constant in the SUT source, copy it
     and note where from); `invoke!` maps ops `{:f :read}` /
     `{:f :write, :value v}` / `{:f :cas, :value [old new]}` onto
     `GET k` / `PUT k v` / `CAS k old new` via
     `client.io().send(...)` / `.sendReadOnly(...)` with
     `Message.valueOf`, decoding replies from
     `reply.getMessage().getContent().toStringUtf8()`; every invocation
     wrapped in a 5 s harness-side timeout; `close!` closes the client.
     Keys come through jepsen `independent` tuples — stringify as-is.
   - `ratis-jepsen.outcome` — **the outcome map** (DESIGN §2.4): a
     single classification function `(classify op-kind result-or-throwable)`
     → `:ok` / `:fail` / `:info` (+ `:error` detail), implementing the
     DESIGN table exactly: reply-success ⇒ `:ok` (but CAS `MISMATCH`/
     `ABSENT` ⇒ `:fail` with `:error :precondition`); NotLeader/
     LeaderNotReady/ResourceUnavailable/GroupMismatch/StateMachine
     exceptions ⇒ `:fail`; Timeout/IOException/AlreadyClosed/interrupt ⇒
     `:info` for writes, `:fail` for reads; **unknown Throwable ⇒
     `:info` for writes (pessimism) + a loud log**. Reads are never
     `:info`.
   - `ratis-jepsen.db` — jepsen `db/DB` + `db/LogFiles` to the §2.6
     contract: install (copy tarball from
     `/ratis-jepsen/sut/ratis-kv/target/ratis-kv-*.tar.gz` on control,
     unpack to `/opt/ratis-kv`), start (contract CLI, stdout →
     `/var/log/ratis-kv.log`, pidfile, await startup-line regex with
     deadline), kill (`kill -9` by pidfile), wipe
     (`/var/lib/ratis-kv`), logs. Pure functions (command strings,
     peers-list construction, startup-regex) separated from the
     side-effecting `jepsen.control` calls so they unit-test without a
     cluster.
   - `ratis-jepsen.core` — `jepsen.cli` entry (`single-test-cmd`)
     assembling a test map with your db + client, `:workload` stubbed
     (a trivial generator or none — Job 04 owns workloads), nemesis
     `noop`.
3. **Unit tests** (no cluster): outcome-map — every row of the DESIGN
   table exercised with real Ratis exception instances where
   constructible, class-appropriate stand-ins where not (document
   which); op↔wire mapping round-trips; db pure functions (command
   lines, peers string `n1=n1:6000,...`, startup regex matches the
   §2.6 line and rejects near-misses).
4. **In-JVM integration test** (`:test` alias; real Ratis, no Docker):
   boot 3 SUT servers in-process on `127.0.0.1` fixed ports of your
   choosing (the SUT's `ServerOptions`/`Main` public API — see
   `jobs/01-sut/01_brief.md` crib notes; Job 01's own smoke test is
   precedent), then through **your** `client` + `outcome` code: write ⇒
   `:ok`; read ⇒ `:ok` with the value; cas mismatch ⇒ `:fail
   :precondition`; then close all three servers and invoke a write ⇒
   classified **`:info`** (this proves the ambiguous-outcome path with
   the library's real exceptions, not fabricated ones).
5. **`harness/README.md`** — layout, how to run tests, what arrives in
   Job 04.
6. **`jobs/03-harness-core/03_report.md`** per `jobs/README.md`.

## File ownership

May create/modify: `harness/**`, `jobs/03-harness-core/03_report.md`.
Nothing else — in particular nothing under `env/**` (Job 02 runs there
in parallel) or `sut/**` (the `mvnw install` step builds it, never
edits it).

**Parallel-safe with: Job 02.**

## Acceptance criteria (each with command + output excerpt in your report)

1. `sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml -q install` then
   `clojure -M:test` (from `harness/`) — all unit + integration tests
   green; report the jepsen version resolved.
2. Outcome-map tests demonstrably cover every row of the DESIGN §2.4
   table (name the test per row in the report).
3. The in-JVM integration test shows the four classifications from
   deliverable 4, including the servers-down write ⇒ `:info` case.
4. `clojure -M:run test --help` (from `harness/`) prints the jepsen CLI
   usage and exits 0.
5. `ratis-jepsen.env-contract` values match DESIGN §2.6 exactly (quote
   them side-by-side in the report).
6. Apache-2.0 headers on all `.clj` files; ownership respected; no
   artifacts committed (`.cpcache/`, `store/` are gitignored already).
7. `jobs/03-harness-core/03_report.md` present per `jobs/README.md`.

## Non-goals

Workloads, generators beyond the stub, nemeses, checkers, Docker
anything, membership ops, follower-targeted reads, the increment op,
CI. Job 04 owns integration against the real topology.

## Notes

- The outcome map is the one place a subtle bug silently corrupts every
  future run's analysis — bias toward explicitness over cleverness, and
  keep the classification function pure (Throwable in, verdict out).
- Jepsen's own `client/Client` protocol has `open!`/`setup!`/`invoke!`/
  `teardown!`/`close!` — `setup!`/`teardown!` may be no-ops here.
- If a Ratis exception type resists construction in unit tests, prefer
  testing dispatch through a minimal subclass over reflection hacks;
  say which types needed it.
