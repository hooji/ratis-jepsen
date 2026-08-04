# Job 03 report — harness core: Clojure skeleton, client + outcome map, db lifecycle

## Summary

The harness foundation now exists: a deps.edn Clojure project on jepsen
0.3.13 and Ratis 3.2.2, with the env-contract constants in one namespace,
a fully unit-tested outcome map, a `RaftClient`-interop jepsen client, the
§2.6 node-lifecycle code, and a `jepsen.cli` entry — plus an in-JVM
integration test that drives three real SUT servers through the harness's
own client and outcome code. The decision a reviewer should look hardest
at: **the outcome map has two rows the DESIGN §2.4 table does not name**,
for `RaftRetryFailureException` — reading the ratis-client 3.2.2 source
showed that under `noRetry` a reply carrying `NotLeaderException` or
`LeaderNotReadyException` is nulled inside the client
(`RaftClientImpl.handleLeaderException`) and surfaces as
`RaftRetryFailureException` with a **null cause** (`noMoreRetries` builds
it only when the recorded throwable is null; any real first-attempt
exception is rethrown as itself). Null-cause RRFE is therefore classified
as a definite `:fail`, and RRFE-with-cause (impossible under `noRetry`,
and unsound to unwrap under multi-attempt policies) as pessimistic `:info`
for writes plus a loud log. The second reviewable decision: the brief
wants `classify` pure while DESIGN wants unknowns logged loudly — resolved
by having pure `classify` return a `::loud` triage message in the verdict
and a thin `classify!` wrapper (used by the client) do the logging.

## What was built

| File | One line |
|---|---|
| `harness/deps.edn` | jepsen 0.3.13, ratis-client + ratis-grpc 3.2.2; `:run` (main entry) and `:test` (cognitect test-runner; adds SUT jar `ratis-jepsen/ratis-kv 0.1.0-SNAPSHOT` test-only) |
| `harness/src/ratis_jepsen/env_contract.clj` | every DESIGN §2.6 value (nodes/voters/pool, ssh user, port, install/storage/log paths, startup-line regex) plus the SUT's fixed group UUID, in one place |
| `harness/src/ratis_jepsen/outcome.clj` | THE OUTCOME MAP: pure `classify` (op kind × wire-reply-or-Throwable → verdict), full table in the ns docstring, `classify!` loud-log wrapper |
| `harness/src/ratis_jepsen/client.clj` | jepsen `Client`: one `RaftClient` per process (`noRetry`), pure `op->request` / `verdict->op` mapping, 5 s harness-side timeout via future+deref, independent-tuple values |
| `harness/src/ratis_jepsen/db.clj` | jepsen `DB`+`LogFiles`+`Kill`: tarball install → `/opt/ratis-kv`, `start-stop-daemon` start with pidfile + startup-line await w/ 30 s deadline, `kill -9` by pidfile, wipe; pure fns (`peers-spec`, `server-args`, `select-tarball`) split from `jepsen.control` effects |
| `harness/src/ratis_jepsen/core.clj` | `jepsen.cli` `single-test-cmd` assembling db + client, noop nemesis, `:generator nil` (Job 04 owns workloads) |
| `harness/test/ratis_jepsen/outcome_test.clj` | one deftest per §2.4 table row with real Ratis exception instances, plus structural sweeps (reads never `:info`, future-wrapper unwrap, loud-strip) |
| `harness/test/ratis_jepsen/client_test.clj` | op↔wire round-trips, including decoding harness requests with the SUT's own `KvCodec` and parsing SUT-encoded replies |
| `harness/test/ratis_jepsen/db_test.clj` | pure-fn tests, startup regex vs realistic line + near-misses, env-contract↔§2.6 pinning test |
| `harness/test/ratis_jepsen/integration_test.clj` | 3 in-JVM SUT servers on 127.0.0.1:26631-3 driven through the harness client+outcome: the four required classifications |
| `harness/README.md` | layout, how to run tests, what arrives in Job 04 |
| `jobs/03-harness-core/03_report.md` | this report |

## How it was verified

All commands from the repo root (or `harness/` where stated), Linux,
OpenJDK 21.0.10, Clojure CLI 1.12.5.1664.

### Criterion 1 — SUT install, then all tests green; jepsen version

```
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml -q install
[... SUT's own codec/smoke/seeded-bug tests run ...]
MVN EXIT: 0

$ cd harness && clojure -M:test
Running tests in #{"test"}
Testing ratis-jepsen.client-test
Testing ratis-jepsen.db-test
Testing ratis-jepsen.integration-test
Testing ratis-jepsen.outcome-test
Ran 31 tests containing 251 assertions.
0 failures, 0 errors.
TEST EXIT: 0
```

Jepsen resolved: **`jepsen/jepsen 0.3.13`** (latest 0.3.x on Clojars at
build time), confirmed on the classpath:

```
$ clojure -Spath | tr ':' '\n' | grep -E "jepsen/jepsen|ratis-(client|grpc)"
/root/.m2/repository/jepsen/jepsen/0.3.13/jepsen-0.3.13.jar
/root/.m2/repository/org/apache/ratis/ratis-client/3.2.2/ratis-client-3.2.2.jar
/root/.m2/repository/org/apache/ratis/ratis-grpc/3.2.2/ratis-grpc-3.2.2.jar
```

(The one stack trace visible in the test output is deliberate: the
`classify-bang-logs-and-strips-loud` test exercises `classify!`'s loud
logging on an unknown `RuntimeException`, which logback prints.)

### Criterion 2 — outcome-map tests cover every §2.4 row

All rows exercised with **real Ratis 3.2.2 exception instances — every
type in the table has a public constructor, so no stand-ins or subclasses
were needed** (`NotLeaderException` and `LeaderNotReadyException` built
over a real `RaftGroupMemberId`/`RaftPeer`; `RaftRetryFailureException`
over a real `RaftClientRequest` from its builder). Row → test
(`harness/test/ratis_jepsen/outcome_test.clj`):

| DESIGN §2.4 row | Test |
|---|---|
| reply isSuccess ⇒ `:ok` (write/cas/read) | `row-reply-success` |
| CAS `MISMATCH`/`ABSENT` ⇒ `:fail` `:error :precondition` | `row-cas-precondition` |
| `NotLeaderException` ⇒ `:fail` | `row-not-leader` |
| `LeaderNotReadyException` ⇒ `:fail` | `row-leader-not-ready` |
| `ResourceUnavailableException` ⇒ `:fail` | `row-resource-unavailable` |
| `GroupMismatchException` ⇒ `:fail` + flag | `row-group-mismatch` |
| `StateMachineException` ⇒ `:fail` + flag | `row-state-machine` |
| `ReadException`/`ReadIndexException` ⇒ read `:fail` (write: impossible ⇒ pessimism + loud) | `row-read-exceptions` |
| `TimeoutIOException`/generic `IOException`/`AlreadyClosedException`/interrupt ⇒ `:info` writes, `:fail` reads | `row-ambiguous-exceptions` (also covers the harness-side `j.u.c.TimeoutException` deadline) |
| unknown Throwable ⇒ `:info` writes (pessimism) + loud log; reads `:fail` | `row-unknown-throwable` (incl. two *unlisted* `RaftException` subtypes proving they hit the loud bucket, not the quiet IO row) |
| *(added; see Deviations)* `RaftRetryFailureException` null cause ⇒ `:fail` | `row-retry-failure-null-cause` |
| *(added)* `RaftRetryFailureException` with cause ⇒ pessimism + loud | `row-retry-failure-with-cause` |
| *(added)* `ERR`/wrong-shape replies ⇒ `:fail` + loud (DESIGN §1.4: a harness bug, not an `:info`) | `row-err-and-malformed-replies` |

Structural guarantees on top: `reads-are-never-info` (sweeps every
throwable in the table plus unknowns and all reply shapes),
`writes-never-fail-on-ambiguity`, `unwraps-future-wrappers`
(`ExecutionException`/`CompletionException` from the harness future),
`classify-bang-logs-and-strips-loud`, `rejects-unknown-op-kind`.

### Criterion 3 — in-JVM integration test: the four classifications

`harness/test/ratis_jepsen/integration_test.clj` boots `n1..n3` on fixed
ports 26631–26633 through the SUT's own CLI path
(`ServerOptions.parse` → `Main.buildServer`, per the Job 01 smoke-test
precedent), then through **the harness's** `client` + `outcome` code
asserts, in order: write ⇒ `:ok`; read ⇒ `:ok` with value 42 (and absent
key ⇒ `:ok` nil); cas `[999 7]` ⇒ `:fail :precondition` with
`:current 42`; cas `[42 43]` ⇒ `:ok`; then closes all three servers and
invokes a write ⇒ **`:info`**. It runs inside criterion 1's
`clojure -M:test` (the `integration-test` namespace above; 0 failures).
The election-phase `NotLeaderException` replies visible in the log are
retried by the test loop through the full client+outcome path — each
attempt classifying as a definite `:fail` — until the leader emerges.

A separate probe (same client code, servers closed) shows what the
servers-down `:info` actually is with the library's real exceptions:

```
DOWN-WRITE 0 => {:type :info, :error :io}
DOWN-WRITE 1 => {:type :info, :error :io}
DOWN-WRITE 2 => {:type :info, :error :io}
```

i.e. a real transport-level `IOException` (grpc UNAVAILABLE wrapped by
the Ratis client) taking the generic-IO ambiguity row — not a fabricated
exception.

### Criterion 4 — CLI usage

```
$ cd harness && clojure -M:run test --help; echo "EXIT CODE: $?"
Usage: lein run -- COMMAND [OPTIONS ...]

Runs a Jepsen test and exits with a status code:

  0     All tests passed
  ...
  -h, --help                                                  Print out this message and exit
      --test-count NUMBER         1                           How many times should we repeat a test?
  ...
  -n, --node HOSTNAME             ["n1" "n2" "n3" "n4" "n5"]  Node(s) to run test on. ...
EXIT CODE: 0
```

(Jepsen 0.3.13's `single-test-cmd` default passes its `test-usage`
*function object* to the printer, rendering as `#object[...]`; `core.clj`
passes the called string instead — see Deviations.)

### Criterion 5 — env-contract ↔ DESIGN §2.6, side by side

| DESIGN §2.6 | Value in DESIGN | `env-contract` var | Value in code |
|---|---|---|---|
| Nodes | `n1..n7`; `n1..n5` initial voters; `n6`/`n7` dormant pool | `all-nodes` / `initial-voters` / `pool-nodes` | `["n1"…"n7"]` / `["n1"…"n5"]` / `["n6" "n7"]` |
| User | `root`, passwordless ssh from control | `ssh-user` | `"root"` |
| Raft port | `6000` on every node | `raft-port` | `6000` |
| Install dir | `/opt/ratis-kv` (`…/bin/ratis-kv`, `…/lib/`) | `install-dir` / `bin-path` / `lib-dir` | `"/opt/ratis-kv"` / `"/opt/ratis-kv/bin/ratis-kv"` / `"/opt/ratis-kv/lib"` |
| Storage dir | `/var/lib/ratis-kv` | `storage-dir` | `"/var/lib/ratis-kv"` |
| Log | stdout captured to `/var/log/ratis-kv.log` | `log-file` | `"/var/log/ratis-kv.log"` |
| Startup line | `ratis-kv server started: id=<id> address=<host:port> storage=<dir> group=<uuid> peers=<list>` | `startup-line-pattern` | `#"ratis-kv server started: id=(\S+) address=(\S+) storage=(\S+) group=(\S+) peers=(\S.*)"` |

One extra constant beyond the §2.6 table lives there deliberately:
`group-uuid` = `724d1912-848e-4e0f-a7e0-abbc16e54704`, copied from
`Main.GROUP_UUID` in `sut/ratis-kv/src/main/java/ratis/jepsen/kv/Main.java`
(provenance noted in its docstring; the client builds its `RaftGroup`
from it). The `env-contract-matches-design-2-6` deftest re-asserts every
value literally, so contract drift breaks the build. The regex was
verified against a line rendered with the SUT's actual formatting —
`group=` prints via `RaftGroupId.toString()` as `group-ABBC16E54704`,
peers as a Java map (`{n1=n1:6000, …}`) — and against seven near-misses
(`starting` line, missing/reordered/empty fields, other process's line),
all rejected (`startup-regex-matches-the-contract-line`,
`startup-regex-rejects-near-misses`).

### Criterion 6 — headers, ownership, no artifacts

```
$ for f in harness/deps.edn harness/src/ratis_jepsen/*.clj harness/test/ratis_jepsen/*.clj; do
    head -1 "$f" | grep -q "Copyright 2026 the ratis-jepsen authors" && echo "OK   $f"; done
OK   harness/deps.edn
OK   harness/src/ratis_jepsen/client.clj
[... all 10 files OK ...]

$ git status --short          # before commit
?? harness/
$ git check-ignore harness/.cpcache && echo ignored
harness/.cpcache
ignored
```

All changes are inside `harness/**` + this report; `sut/**` was built by
`mvnw install` but never edited; nothing under `env/**` was touched. No
`target/`, `.cpcache/` or `store/` content is committed.

### Criterion 7 — this report

`jobs/03-harness-core/03_report.md`, sections per `jobs/README.md`.

## Deviations from the brief

1. **Two `RaftRetryFailureException` rows added to the outcome map**
   (plus their tests). The brief/DESIGN table says NotLeader/
   LeaderNotReady ⇒ `:fail`; mechanically, at ratis 3.2.2 under
   `noRetry`, those two never reach the caller as themselves. Verified
   in the 3.2.2 sources: `BlockingImpl.sendRequest` calls
   `RaftClientImpl.handleLeaderException`, which returns **null** for a
   reply bearing either exception; the retry loop then records a null
   throwable, and `RaftClientImpl.noMoreRetries` returns the original
   exception only when the throwable is non-null (`attemptCount == 1 &&
   throwable != null`), else `RaftRetryFailureException(…, null cause)`.
   So: RRFE with null cause ⇒ definite `:fail`
   (`:error :not-leader-or-not-ready`); RRFE with a cause (impossible
   under `noRetry`; under bounded retries earlier attempts may have
   applied even when the last cause looks definite) ⇒ `:info` for writes
   + loud log, never recursion into the cause. The direct
   NotLeader/LeaderNotReady rows are kept as well, so the classifier
   doesn't depend on which wrapping the library chooses. The in-JVM test
   exercises the funnel live (election-phase write attempts classify
   `:fail`).
2. **Purity vs. loud logging.** The brief demands a pure classification
   function; DESIGN §2.4 demands loud logs on unknowns. `classify` is
   pure and returns any triage message under a namespaced `::loud` key;
   `classify!` (what `invoke!` calls) logs it at error level with the
   stack trace and strips the key. Unit tests assert `::loud`'s
   presence/absence per row.
3. **`ERR` replies classified explicitly.** Not a §2.4 table row, but
   DESIGN §1.4 pins the semantics ("a harness bug, not an `:info`"):
   `ERR <reason>` — and any reply shape impossible for the op kind — is
   a definite `:fail` plus loud log, for all op kinds.
4. **`group-uuid` lives in `env-contract`, not `client`.** The brief
   describes env-contract as the §2.6 constants and tells the client to
   copy the UUID from the SUT source; since the UUID is a cross-component
   contract value (compiled into every peer), it sits beside the other
   contract constants with provenance documented, and the §2.6
   side-by-side above marks it as an extra.
5. **`:usage` workaround in `core.clj`.** Jepsen 0.3.13's
   `single-test-cmd` defaults `:usage` to the `test-usage` function
   object, which prints as `#object[…]` at the top of `--help`; the
   harness passes `(cli/test-usage)` (the string) so usage renders
   properly. Behavior otherwise identical.
6. **Read of an absent key ⇒ `:ok` with `:value nil`.** The table row
   just says reads ⇒ `:ok`; nil-for-absent is the standard jepsen
   register convention and is what the checker will model (noted here so
   Job 04 wires the model accordingly).

## Known gaps and risks

- **The db code has never touched a real node** (by design — Job 04
  verifies against containers). Assumptions a container run will test:
  `start-stop-daemon` exists on the node image (Debian base per DESIGN
  §3); the repo is mounted at `/ratis-jepsen` on control
  (`db/tarball-dir`); ssh user is root so no sudo wrapping is needed
  anywhere.
- **`await-startup!` matches any startup line in the log.** After a
  crash-restart *without* a wipe (M1's nemesis), a stale line from the
  previous boot would satisfy the await immediately. M1 should count
  occurrences (or truncate the log on start). Harmless in M0's
  fresh-install flow.
- **Pidfile path `/run/ratis-kv.pid`** is a harness-internal choice (the
  §2.6 table names no pidfile); it lives in `db.clj`, not env-contract.
  Anything else managing the process must agree or use `db.clj`.
- **Fixed integration-test ports 26631–26633**: a port collision on a
  busy machine fails the test spuriously (rerun). Chosen over ephemeral
  ports per the brief's "fixed ports of your choosing".
- **Client churn on `:info`**: the client doesn't implement jepsen's
  `Reusable`, so jepsen closes/reopens it after `:info` ops — a fresh
  `ClientId` each time. Irrelevant under `noRetry` (no retry-cache
  reliance) but worth revisiting for M3's increment workload (PLAN Q2).
- **Servers-down `:info` arrives as generic `:io`** (grpc UNAVAILABLE
  wrapped in `IOException`). If a Ratis upgrade changes the wrapping, the
  unknown-throwable bucket still classifies writes `:info` (pessimism) —
  safe, but watch for new loud logs after version bumps.
- `db/select-tarball` picks the lexicographically last of multiple
  matching tarballs (with a warning); fine for `ratis-kv-<semver>` names,
  not a general version sort.

## Suggestions (out of scope)

- M1 (crash nemesis): make the startup await restart-safe — count
  startup-line occurrences per boot, or truncate the log in `start!`.
- Job 04: automate DESIGN §5.3's "eyeball" check as a checker assert —
  `:info` ops must not appear outside nemesis-active windows.
- Consider implementing `jepsen.db/Pause` alongside `Kill` when the M1
  SIGSTOP nemesis lands (two `c/exec` calls; deliberately not added now
  per scope discipline).
- Jepsen upstream: `single-test-cmd`'s default `:usage` prints the
  function object — tiny fix, worth a PR someday.
