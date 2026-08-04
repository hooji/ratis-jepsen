# Review 03 report — Job 03: harness core: Clojure skeleton, client + outcome map, db lifecycle

Worker PR: #4 (`claude/harness-core-brief-urftoi`, head `aa8430e`).
Reviewed in a detached worktree of that head; nothing was pushed to the
worker's branch. Review environment: Linux, OpenJDK 21.0.10, Clojure CLI
1.12.5.1664 (not preinstalled here — installed to reproduce; same version
the worker used).

## Verdict: MERGE

## Justification

All seven acceptance criteria reproduce independently: the SUT installs,
the full suite is green (31 tests / 251 assertions — three consecutive
runs), the CLI prints usage and exits 0, every DESIGN §2.4 row has a
named test using real Ratis 3.2.2 exception instances, the env-contract
matches §2.6 value-for-value (group UUID checked against the SUT source
itself), headers/ownership/artifact hygiene are clean, and the report is
accurate and complete. The one potentially blocking question — whether
`RaftRetryFailureException` with `:fail` can ever swallow an ambiguous
(possibly-applied) failure — is settled **sound** by the ratis-3.2.2
client sources and confirmed by live probes: under `noRetry`, every
non-null first-attempt failure (including the dangerous mid-call
`TimeoutIOException`) surfaces **as itself**, and RRFE arises only with a
null cause, only from the NotLeader/LeaderNotReady nulled-reply funnel —
a definite pre-append rejection. Findings are all non-blocking.

## What I verified

### Criterion 1 — SUT install + full suite green; jepsen version

```
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml -q install   # exit 0
$ cd harness && clojure -M:test
Ran 31 tests containing 251 assertions.
0 failures, 0 errors.          # exit 0 — reproduced THREE times (see emphasis 3)
```

Jepsen resolved: `jepsen/jepsen 0.3.13` (`clojure -Spath` shows
`jepsen/jepsen/0.3.13/jepsen-0.3.13.jar`). Clojars'
`maven-metadata.xml` confirms 0.3.13 is the newest 0.3.x, so "latest
0.3.x resolvable" is accurate, and the pin is exact (no
RELEASE/floating).

### Criterion 2 / emphasis 2 — outcome-table row coverage

Read `outcome_test.clj` against the report's row→test table: accurate.
Every §2.4 row plus the three added rows (`RaftRetryFailureException`
null/with-cause, ERR/wrong-shape replies, and the harness-timeout
`j.u.c.TimeoutException` inside `row-ambiguous-exceptions`) has a
deftest, and each Ratis type is constructed directly via its public
constructor (`NotLeaderException` over a real
`RaftGroupMemberId`/`RaftPeer`, RRFE over a builder-built
`RaftClientRequest`) — the "no stand-ins needed" claim is true; the
suite passing proves constructibility. Structural sweeps
(`reads-are-never-info`, `writes-never-fail-on-ambiguity`,
`unwraps-future-wrappers`) exist and pass. Also spot-checked that two
*unlisted* `RaftException` subtypes (`LeaderSteppingDownException`,
`NotReplicatedException`) land in the loud-pessimism bucket, not the
quiet IO row.

### Criterion 3 / emphasis 3 — integration test through the real stack

`integration_test.clj` drives everything through `jepsen.client/open!` /
`invoke!` on the worker's `RatisKvClient`, which calls
`outcome/classify!` — no raw interop side-channels (the only direct SUT
interop is booting/closing the three servers, i.e. the fixture). The
four classifications are asserted in order, including the
all-servers-down write ⇒ `:info`. Flakiness: **three consecutive
`clojure -M:test` runs, all green**. Port-collision behavior (fixed
ports 26631–3): holding 26631 with a socket and running the suite makes
Ratis's gRPC layer call `ExitUtils.terminate` — the **whole test JVM
exits 1** ("Terminating with exit status 1: Failed to start Grpc
server") with no test summary. Loud and unmistakably red, though
harsher than the report's "fails the test spuriously (rerun)" wording
implies (finding 2).

### Criterion 4 — CLI usage

```
$ cd harness && clojure -M:run test --help   # exit 0
Usage: lein run -- COMMAND [OPTIONS ...]
Runs a Jepsen test and exits with a status code: ...
```

Usage renders as a string (the `:usage` workaround works; no
`#object[...]`).

### Criterion 5 / emphasis 4 — contract fidelity, both directions

`env_contract.clj` vs DESIGN §2.6, line-by-line: nodes
n1..n7/voters n1..n5/pool n6-n7, user `root`, port `6000`,
`/opt/ratis-kv` (+bin/lib), `/var/lib/ratis-kv`,
`/var/log/ratis-kv.log` — all exact; re-pinned by the
`env-contract-matches-design-2-6` deftest. Startup regex matches the
§2.6 line shape with five non-empty ordered captures and is verified in
`db_test.clj` against a line using the SUT's real rendering
(slf4j prefix, `RaftGroupId.toString()` = `group-ABBC16E54704`, Java-map
peers) plus **seven rejected near-misses** ("starting", missing/
reordered/empty fields, other process). I checked the print site myself:
`Main.java:101` logs exactly
`ratis-kv server started: id={} address={} storage={} group={} peers={}`.
Group UUID: `env_contract.clj` pins
`724d1912-848e-4e0f-a7e0-abbc16e54704`; **verified against the SUT
source directly** — `Main.java:64`:
`public static final UUID GROUP_UUID = UUID.fromString("724d1912-848e-4e0f-a7e0-abbc16e54704")`.
Match.

### Emphasis 5 — `db.clj` by inspection + unit tests

- **Tarball install**: `select-tarball` globs `ratis-kv-.*\.tar\.gz`
  (version-robust), throws with build instructions on zero matches
  (unit-tested). I inspected the real built tarball: `bin/` and `lib/`
  at top level, so untarring into `/opt/ratis-kv` yields the contract
  `bin-path`. `install!` targets only contract paths.
- **Peers string**: `peers-spec` produces
  `n1=n1:6000,n2=n2:6000,n3=n3:6000,n4=n4:6000,n5=n5:6000` (unit-tested,
  contract-exact).
- **Start**: read jepsen 0.3.13's `cu/start-daemon!` — it appends
  stdout+stderr to `:logfile` (`>> logfile 2>&1`), i.e. the contract log
  path; `:match-executable? false` is correct for an exec-ing launcher
  script. `await-startup!` polls the contract regex with a bounded 30 s
  deadline and throws `ex-info` on expiry — a wedged start fails fast.
- **Kill**: jepsen's `stop-daemon!` single-arg form defaults to signal
  **9** — `kill -9` by pidfile, then removes the pidfile. Contract-true.
- **Wipe**: `(c/exec :rm :-rf env/storage-dir env/log-file)` — both are
  fixed contract constants and `c/exec` shell-escapes every argument; no
  unquoted or wrong path (I checked for exactly this — it's clean).
  Wiping the log in `teardown!` is safe: jepsen 0.3.13's `with-db`
  snarfs log files (`with-log-snarfing`) *before* the finally-block
  teardown.
- **LogFiles**: returns `["/var/log/ratis-kv.log"]` — the contract log.

### Emphasis 6 — deps.edn hygiene

`ratis-jepsen/ratis-kv` sits only in `:test`'s `:extra-deps`; verified
on classpaths, not just by reading: base/`:run` `clojure -Spath` has
**no ratis-kv jar**, `clojure -A:test -Spath` has it. Jepsen pinned to
exact `0.3.13`.

### Emphasis 1 — `RaftRetryFailureException` soundness (the blocking-risk item)

Settled from the `ratis-3.2.2` sources (Maven Central sources jars for
`ratis-client`, `ratis-grpc`, `ratis-common`). The lines that settle it:

`RaftClientImpl.noMoreRetries` (RaftClientImpl.java:346–353):

```java
IOException noMoreRetries(ClientRetryEvent event) {
    final int attemptCount = event.getAttemptCount();
    final Throwable throwable = event.getCause();
    if (attemptCount == 1 && throwable != null) {
      return IOUtils.asIOException(throwable);
    }
    return new RaftRetryFailureException(event.getRequest(), attemptCount, retryPolicy, throwable);
}
```

with `IOUtils.asIOException` (IOUtils.java:54–57) returning an
`IOException` **unchanged** (`t instanceof IOException ? (IOException)t
: new IOException(t)`), and `PendingClientRequest.newRequest()`
(RaftClientImpl.java:92–97) incrementing the attempt counter to **1 on
the first attempt**. In `BlockingImpl.sendRequestWithRetry`
(BlockingImpl.java:105–131): reply-borne `GroupMismatch/StateMachine/
TransferLeadership/LeaderSteppingDown/AlreadyClosed/AlreadyExists/
SetConfiguration` exceptions are rethrown as-is (114–117); **every other
IOException is caught into `ioe`** (118–120) and — because under
`noRetry` the attempt count is 1 and `ioe` is non-null — is returned
as-is by `noMoreRetries`. The only way to reach `noMoreRetries` with a
**null** throwable is `handleLeaderException`
(RaftClientImpl.java:379–393):

```java
/** @return null if the reply is null or it has
 *  {@link NotLeaderException} or {@link LeaderNotReadyException} ... */
RaftClientReply handleLeaderException(RaftClientRequest request, RaftClientReply reply) {
    if (reply == null || reply.getException() instanceof LeaderNotReadyException) {
      return null;
    }
    final NotLeaderException nle = reply.getNotLeaderException();
    ...
```

i.e. a **reply arrived** carrying NotLeader/LeaderNotReady — a definite
pre-append rejection. The remaining hazard would be the transport
returning a null reply non-exceptionally: `GrpcClientRpc.sendRequest`
(GrpcClientRpc.java:138–188) cannot — it returns a received reply or
throws (`TimeoutIOException` on mid-call deadline, `InterruptedIOException`
on interrupt, `AlreadyClosedException` on stream-complete-without-reply,
unwrapped `IOException` on stream error). **Enumeration under `noRetry`,
therefore: RRFE(null cause) ⇔ NLE/LNRE reply (definite ⇒ `:fail`
sound); every possibly-applied failure — mid-call `TimeoutIOException`
included — surfaces directly as itself ⇒ takes the `:info` rows. No
possibly-applied failure mode maps to `:fail`.** The worker's two added
rows (null ⇒ `:fail`; with-cause ⇒ pessimistic `:info` + loud, since it
implies a multi-attempt policy where earlier attempts may have applied)
are exactly right, as is keeping the direct NLE/LNRE rows.

### Probes (beyond the worker's tests)

All run against the worker's actual namespaces on the `:test` classpath:

1. **Hand-built `RRFE(TimeoutIOException)`** through `classify`:
   write/cas ⇒ `:info` (+loud), read ⇒ `:fail`. `RRFE(null)` ⇒ `:fail
   :not-leader-or-not-ready`. Matches the emphasis-1 conclusion.
2. **Live NLE funnel**: 3-server in-JVM cluster; single-node clients per
   node. Leader (n2) ⇒ `:ok`; both followers ⇒ `:fail
   :not-leader-or-not-ready` — the null-cause RRFE funnel observed with
   real 3.2.2 exceptions, end to end.
3. **Quorum loss, minority alive** (closed 2 of 3): three writes ⇒
   `:info :io` in **3–5 ms** each — sound verdict, no hang anywhere near
   the 5 s harness timeout.
4. **Malformed op values** through `invoke!` (nil key, nil/non-long
   value, non-long CAS update, spaced key): every one ⇒ loud `:fail
   [:unexpected-reply "ERR ..."]` — a harness bug surfaces loudly, never
   silently and never as `:ok`/`:info`.

### Criterion 6/7 — hygiene, ownership, report

Apache-2.0 headers on all 10 `.clj` files + `deps.edn`. Diff touches
only `harness/**` + `jobs/03-harness-core/03_report.md` (12 files;
`git ls-tree` shows no artifacts — no `.cpcache/`, `target/`, `store/`).
Worker report has all required sections in order; every claim I tested
reproduced, including the down-write `:info` probe output and the
`:usage` deviation.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking | `harness/src/ratis_jepsen/outcome.clj:117–133` | `parse-reply` docstring claims "Total — never throws", but a `VAL`/`MISMATCH` numeral exceeding Long range throws `NumberFormatException` out of `classify` (verified: `(parse-reply "VAL 99999999999999999999")` ⇒ NFE). Unreachable from the real SUT (KvCodec encodes Java longs), and jepsen's worker-level catch would surface it loudly as a crashed op — but the totality claim is false as written. Use `clojure.core/parse-long` or catch NFE ⇒ `[:unparseable s]`. |
| 2 | non-blocking | `harness/test/ratis_jepsen/integration_test.clj:40` | Port collision on the fixed ports doesn't fail the test — it kills the whole runner JVM (Ratis `ExitUtils.terminate` ⇒ exit 1, "Failed to start Grpc server", no test summary). Loud and red either way; report's "fails the test spuriously (rerun)" slightly understates it. |
| 3 | non-blocking (disclosed) | `harness/src/ratis_jepsen/db.clj:83–98` | `select-tarball` picks the lexicographically last of multiple tarballs — not a version sort (e.g. `0.10.0` < `0.2.0` lexically). Warned + disclosed in the worker report; fine for M0's single-artifact flow. |
| 4 | non-blocking (disclosed) | `harness/src/ratis_jepsen/db.clj:129–148` | `await-startup!` is satisfied by a stale startup line after a crash-restart without wipe — M1's crash nemesis must count occurrences or truncate on start. Harmless in M0's fresh-install flow; already in the worker's Known Gaps with the right fix sketched. |

## Suggestions (non-blocking)

- Fix finding 1 cheaply with `parse-long` (returns nil on overflow) —
  keeps the totality claim honest.
- `:test` classpath carries two SLF4J providers (SUT's slf4j-simple +
  jepsen's logback; simple wins, warning printed). Cosmetic; an
  exclusion on the test-only SUT dep would silence it.
- When Job 04 wires real workloads, consider asserting in the checker
  that `:info` ops appear only inside nemesis windows (the worker
  suggests the same — worth adopting).
