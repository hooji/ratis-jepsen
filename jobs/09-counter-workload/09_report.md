# Job 09 report — M3: the exactly-once increment workload

## Summary

The harness now tests the property Ratis was chosen for: `--workload
counter` drives deliberately non-idempotent `ADD`s through the standard
bounded same-callId retry client under leader kills, and a per-key
bounds checker convicts any double-count or loss — an `:ok` add must
count exactly once, an `:info` add 0-or-1 times, a `:fail` add exactly
zero (the outcome map's definite-fail guarantee, now load-bearing).
Exactly-once **held** under the default 60 s retry-cache window across
every green gate (retry counts quoted per run — the new evidence law
fails any dedup run whose client never actually retried), and the Q14
red-by-design run made the documented boundary observable: with the
server window shrunk to 2000 ms and the client's inter-attempt delay
raised to 3000 ms, retried adds double-applied and the counter checker
convicted — the calibration evidence for the StateStore L3 provider's
Indeterminate-retry rule, exactly as PLAN Q14 wanted. Decisions to
look hardest at: (1) the checker is an explicit per-key bounds checker,
not jepsen's stock counter checker (choice documented below); (2) retry
evidence comes from an observing `RetryPolicy` wrapper that counts the
library's otherwise-invisible internal attempts into a `:retries` field
on each completion; (3) the counter workload carries the sustained-
stream defaults (rate 1.4, 800 ops/key) so CI's bare `counter-crash`
token cannot burn its op budget before the first kill window and
legally see zero retries.

## What was built

| File | One line |
|---|---|
| `sut/ratis-kv/src/main/java/ratis/jepsen/kv/KvCodec.java` | `ADD <k> <delta>` request form (decode/encode; malformed variants covered) |
| `sut/ratis-kv/src/main/java/ratis/jepsen/kv/KvStateMachine.java` | `Add` apply case: `merge(key, delta, Long::sum)`, reply `VAL <total-after-this-apply>`; read-path ADD replies ERR |
| `sut/ratis-kv/src/main/java/ratis/jepsen/kv/ServerOptions.java` | `--retry-cache-expiry-ms` (positive long; absent ⇒ null) |
| `sut/ratis-kv/src/main/java/ratis/jepsen/kv/Main.java` | wires the flag to `RaftServerConfigKeys.RetryCache.setExpiryTime` — only when present; usage text |
| `sut/ratis-kv/src/test/java/ratis/jepsen/kv/KvCodecTest.java` | ADD round-trips + malformed cases |
| `sut/ratis-kv/src/test/java/ratis/jepsen/kv/RatisKvSmokeTest.java` | real-wire ADD semantics: absent=0, accumulation, negative delta, ERR paths |
| `sut/ratis-kv/src/test/java/ratis/jepsen/kv/ServerOptionsRetryCacheTest.java` | new: flag parse/validation; wired property = 2000 ms; ABSENT leaves the Ratis 60 s default |
| `harness/src/ratis_jepsen/outcome.clj` | `:add` op kind (write-kind; `VAL` ⇒ `:ok` with the total under `:observed`; all other replies protocol violations) |
| `harness/src/ratis_jepsen/client.clj` | `:add` wire mapping; `:observed` merge; `counting-retry-policy` (observer around the standard policy → `:retries` per completion); `--retry-delay-ms` plumb + `invoke-deadline-ms` (auto-widened so the Q14 span hits the library's exhaustion, not the harness axe) |
| `harness/src/ratis_jepsen/checker.clj` | per-key counter bounds checker (+ duplicate-observed-total assertion) and the retry-evidence checker (distinct `:no-retry-evidence` failure) |
| `harness/src/ratis_jepsen/workload/counter.clj` | new: the workload — 3:1 add/read mix, deltas 1..5, quiesce + final reads; retry evidence required for fault-bearing runs |
| `harness/src/ratis_jepsen/db.clj` | `--retry-cache-expiry-ms` plumbed through server/join args (test lever, absent by default) |
| `harness/src/ratis_jepsen/core.clj` | workload registration; `--retry-delay-ms` / `--retry-cache-expiry-ms` CLI; counter joins the sustained-defaults rule |
| `harness/test/…` | counter conviction fixtures (double-count, lost update, 0-or-1 `:info`, pending, `:fail` exclusion, mid-run bounds, duplicate totals, ABSENT-as-0), retry-evidence verdicts, `:add` outcome rows, wire mapping, deadline derivation, counting-policy pass-through |
| `.github/workflows/jepsen.yml` | `counter-<kind>` scenario translation (itemized below) + `counter-crash` in the default sweep |
| `docs/RUNS.md` | M3 ledger entries |
| `jobs/09-counter-workload/09_report.md` | this report |

## The SUT diff (itemized)

Two independent pieces, both minimal:

1. **`ADD`** (~25 lines of main code): one codec request form, one
   apply case in the state machine (`Long::sum` merge; reply carries
   the post-apply total — on a deduplicated retry the server returns
   the CACHED original reply, which is what makes the total a
   repliedIndex probe), one read-path guard.
2. **`--retry-cache-expiry-ms`** (~20 lines): parse + one conditional
   property call. The absent-flag case is pinned by test to leave the
   Ratis default untouched — the production profile never shrinks the
   window; only the Q14 run does, deliberately violating the key's own
   documented contract (`RaftServerConfigKeys.RetryCache`: "We should
   set expiry time longer than total client retry to guarantee
   exactly-once semantic").

## The workflow diff (itemized)

Two hunks in `.github/workflows/jepsen.yml`:

1. The scenarios input's default gains `counter-crash`, and its
   description documents the token form.
2. The test step translates `counter-<kind>` tokens to
   `--workload counter --nemesis <kind>`; bare tokens run exactly the
   old command (register workload), byte-identical.

## The checker (brief: "document the choice")

An explicit per-key bounds checker (`ratis-jepsen.checker/counter`),
not jepsen's stock counter checker, because:

- our outcome map guarantees a `:fail` add was **definitely not
  applied** (Reviews 03/05's soundness analysis made that row
  trustworthy), which tightens the upper bound in a way a generic
  checker cannot assume;
- per-key independent histories keep the interval arithmetic exact and
  cheap (no cross-key blowup);
- the workload's final-read phase makes the generic rule subsume
  final-value exactness, so one rule covers everything.

The rule, per `:ok` read (linearizable reads; positive deltas):

```
lower = Σ deltas of :ok adds completed before the read's invocation
upper = Σ deltas of non-:fail adds invoked before the read's completion
        (:ok exactly once + :info/pending 0-or-1)
convict if value < lower (lost update) or value > upper (double count)
```

plus a second, independent assertion: **no two `:ok` adds may report
the same post-apply total** (`ADD` replies `VAL <new>`; with adds-only
keys and positive deltas every apply's total is unique and a
deduplicated retry returns the cached original — a shared total means
the reply cache handed one op another op's reply, the
repliedIndex-linearizability signal RATIS-2542 names). Reads of an
untouched counter reply `ABSENT` and count as reads of 0 — found live:
the first shakedown's early reads raced their key's first add and
NPE'd the original bounds code on 2 of 5 keys (fixed + regression
tests; that run also exposed that jepsen 0.3.13 exits 0 when analysis
*crashes* — "Errors occurred during analysis, but no anomalies found"
— one more reason the checker is now total; see Known gaps).

## Retry evidence (the dedup law)

The library's same-callId retries are internal to `RaftClient` and
invisible to the history, so the client's retry policy is wrapped in an
observer: every `handleAttemptFailure` call — one per failed attempt
the policy is consulted about — bumps a per-client counter, and each
invocation's delta lands on its completion as `:retries`. The
`retry-evidence` checker requires a nonzero total for every
fault-bearing counter run (`:no-retry-evidence` otherwise); the
observer provably passes the inner policy's decisions through unchanged
(unit-tested), so the measured client is the same client the register
workload has used since M1.

## The Q14 run (the L3 Indeterminate-rule calibration)

The mechanics, verified in ratis-3.2.2 source before the run: the
server retry cache is a Guava cache with
`expireAfterWrite(expirytime)`; an entry written at append/apply
expires on that clock on every replica, so a same-callId retry arriving
after expiry finds no entry — on ANY leader, failover or not — and is
appended and applied again. The client sees one `:ok`; the delta
counted twice. The run: `--retry-cache-expiry-ms 2000` (server) +
`--retry-delay-ms 3000` (client) under leader-biased crash cycles —
first attempts apply-then-lose-their-reply when the leader dies, the
retry lands ≥3 s later against an expired window. The invocation
deadline auto-widens (`invoke-deadline-ms`) so the full 4-attempt span
plays out inside the library.

TBD-Q14-RESULT

## How it was verified

Versions: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`,
JDK 21. Commands from the repo root.

### Criterion 1 — suites green

```
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml verify
Tests run: 2 JoinModeTest, 42 KvCodecTest (ADD round-trips + malformed),
  1 KvStateMachineLifecycleTest, 1 RatisKvSmokeTest (real-wire ADD),
  3 ServerOptionsRetryCacheTest, 2 StaleReadsSeedBugTest — 51 total
exit 0
$ cd harness && clojure -M:test
Ran 93 tests containing 891 assertions.
0 failures, 0 errors.        (exit 0)
```

Was 77/823 after Job 08. The required conviction fixtures are all
unit-level: `counter-convicts-a-double-count` (upper bound),
`counter-convicts-a-lost-update` (lower bound),
`counter-info-adds-count-zero-or-once` (5 legal / 7 legal / 9
convicts), `counter-fail-adds-are-definitely-excluded`,
`counter-duplicate-observed-totals-convict`, and the retry-evidence
verdicts including the distinct `:no-retry-evidence` failure.

TBD-RUN-TABLE

## Deviations from the brief

1. **The counter workload carries kind-level workload defaults**
   (rate 1.4, ops-per-key 800 — the Job 08 sustained-stream pattern)
   rather than the global 10/300. CI dispatches bare tokens with no
   flags; at rate 10 the op budget burns in ~25 s, most crash cycles
   land after the ops end, and a healthy run could legally present
   zero retries — the evidence law would convict CI for a scheduling
   accident. Explicit `--rate`/`--ops-per-key` still win.
2. **Criterion 4's zero-retry demonstration is the unit fixture**
   (`retry-verdict-decision`: a zero-total dedup run fails
   `:no-retry-evidence`), as the brief explicitly allows. A live
   defanged run would need a fault schedule engineered to never
   overlap the op phase — the same scheduling accident deviation 1
   exists to prevent.

## Known gaps and risks

- **jepsen 0.3.13 exits 0 when a checker crashes** ("Errors occurred
  during analysis, but no anomalies found", `:valid? :unknown` at the
  top level) — observed live on the first shakedown. A crashing
  checker must never be a green CI run. Mitigated here by making the
  counter checker total (nil-read fix + fabricated-history unit
  coverage); a harness-level exit-code hardening (treat `:unknown` as
  nonzero) would belong to a future job.
- **The retry counter counts failed attempts, not retry decisions**:
  the policy is consulted once per failure, including the one that
  exhausts. For evidence purposes (nonzero ⇔ retries exercised) the
  distinction is immaterial; the ledger quotes the number as
  "retry-policy consultations".
- **`:observed`-total distinctness assumes adds-only keys and positive
  deltas** — both properties of this workload by construction; the
  checker docstring pins the assumption.
- **The Q14 conviction margin depends on `:info` slack**: a double
  only convicts once doubles exceed the never-applied share of
  `:info`/pending adds. The gate run's numbers (quoted in the ledger)
  cleared the margin comfortably; a marginal future run should raise
  kill frequency or lower the expiry further.

## Suggestions (out of scope)

- **Exit-code hardening**: run.sh or the harness could map jepsen's
  `:unknown` analyses to a distinct nonzero exit so checker crashes
  and OOMs can never read as green (the Job 07 knossos OOM exited 2;
  this shakedown's checker crash exited 0 — the inconsistency is the
  problem).
- **Retry-cache metrics cross-check**: the server exposes retry-cache
  hit counts via `MetricRegistries.global()` (PLAN Q12); scraping them
  per run would corroborate the client-side `:retries` evidence with
  server-side dedup counts.
- **A `--retry-cache-expiry-ms`-aware liveness note**: the Q14 run's
  25 s invocation deadline stretches attempt gaps beyond the liveness
  checker's `max-attempt-gap-s`, so stall chains break and liveness is
  effectively unfenced there; harmless for a red-by-design run, worth
  a knob if a green long-delay run ever matters.
- **M4 prep**: the counter workload is the right probe for lazyfs
  lost-write runs — a fsync-lost `:ok` add is exactly a lost update
  the bounds checker convicts.

## Environment notes (this execution sandbox, not the repo)

Same accommodations as Jobs 04–08 (uncommitted): dockerd restarted for
the new session; the Job 08 env image and container set reused
(`run.sh up` restarts the stopped containers; control's seeded
`/root/.m2` and side-loaded gnuplot survive container restarts).
