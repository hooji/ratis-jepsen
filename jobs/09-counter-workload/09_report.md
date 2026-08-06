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
fails any dedup run whose client never actually retried). The Q14
red-by-design run then made the documented boundary observable — but
only on the fourth attempt, and the three misses are the report's
second finding: **the expiry hazard is timeout-shaped, not
crash-shaped**. Kill -9 and minority pauses cannot produce the
applied-but-reply-lost population (the append-to-reply span is ~2 ms;
a frozen leader is deposed before its unread requests append), so a
new pinned-cycle `quorum-pause` nemesis freezes every follower and
lets the live leader append a whole window of uncommittable adds —
their delayed same-callId retries then meet expired cache entries
(server window 500 ms, client delay 5 s), are appended again, and the
checker convicts on **all five keys** (~226 double-applied delta mass
behind 1895/1895 clean `:ok`s) — the empirical calibration for the
StateStore L3 provider's Indeterminate-retry rule, exactly as PLAN Q14
wanted. Decisions to look hardest at: (1) the checker is an explicit
per-key bounds checker, not jepsen's stock counter checker, and it
additionally pins the state at every apply through the totals `ADD`
reports (choice documented below); (2) retry evidence comes from an
observing `RetryPolicy` wrapper that counts the library's
otherwise-invisible internal attempts into a `:retries` field on each
completion; (3) the counter workload carries the sustained-stream
defaults (rate 1.4, 800 ops/key) so CI's bare `counter-crash` token
cannot burn its op budget before the first kill window and legally see
zero retries.

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
| `harness/src/ratis_jepsen/checker.clj` | per-key counter bounds checker — reads AND every `:ok` add's reported total interval-checked, plus duplicate-total assertion — and the retry-evidence checker (distinct `:no-retry-evidence` failure) |
| `harness/src/ratis_jepsen/workload/counter.clj` | new: the workload — 3:1 add/read mix, deltas 1..5, quiesce + final reads; retry evidence required for fault-bearing runs |
| `harness/src/ratis_jepsen/nemesis.clj` | `quorum-pause` kind (pinned calm 20 s / freeze 8 s; SIGSTOP the whole follower set, leader-census-driven) — the Q14 reply-loss producer; fault pair gates liveness |
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

The rule, per observation (linearizable reads; positive deltas):

```
lower = Σ deltas of :ok adds completed before the observation's invocation
upper = Σ deltas of non-:fail adds invoked before its completion
        (:ok exactly once + :info/pending 0-or-1)
convict if value < lower (lost update) or value > upper (double count)
```

applied to **two observation sources**: every `:ok` read, and — second
pass, added when the Q14 forensics needed it by hand — every `:ok`
add's reported total, whose pre-state (total − own delta) is checked
against the same bounds with the add itself excluded. The totals pin
the state at every single apply, so doubles convict even where no read
lands nearby (the Q14 conviction's mid-run reads plus ~1450 pinned
totals per run made final reads a bonus, not a dependency). Plus a
third, independent assertion: **no two `:ok` adds may report the same
post-apply total** (`ADD` replies `VAL <new>`; with adds-only
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

The mechanics, verified in ratis-3.2.2 source: the server retry cache
is a Guava cache with `expireAfterWrite(expirytime)`, rebuilt at apply
on every replica; a same-callId retry arriving after expiry finds no
entry — on any leader — and is appended again, and
`applyLogToStateMachine` applies every STATEMACHINELOGENTRY
unconditionally (no apply-time dedup), so both copies apply.

**It took three attempts to reach the boundary, and the failures are
themselves the calibration finding** (all four runs preserved; full
narrative in the ledger):

1. Crash + expiry 2000 ms / delay 3000 ms: green — every retry
   deduplicated (`…counter-crash/111221.680Z`).
2. Crash + expiry 500 ms / delay 5000 ms: green again; per-key
   forensics (max observed total vs `:ok` sum) proved literally zero
   doubles. **Kill -9 cannot produce the hazard's precondition** — the
   applied-but-reply-lost population — because append → replicate →
   commit → reply spans ~2 ms; a kill lands inside that sliver for
   ~0.1 in-flight ops per kill (`…counter-crash/112532.723Z`).
3. Minority pause: green — a paused LEADER is deposed ~2 s into a 5 s
   freeze and its unread socket buffers never appended
   (`…counter-pause/113333.439Z`).
4. **`quorum-pause`** (a new, pinned-cycle nemesis kind added for
   exactly this): SIGSTOP every follower, keep the leader alive —
   it appends the whole window's adds without committing them, every
   client times out (mass reply-loss with surviving application), the
   stalled entries commit at resume, and the 5 s-delayed retries meet
   expired entries and re-append. **Convicted on all five keys**
   (`…counter-quorum-pause/114319.903Z`, exit 1): every violation
   `:double-count`; the first, verbatim —

   ```
   {:kind :double-count, :read {:final? false, :value 126}, :lower 121, :upper 121}
   ```

   — a linearizable read of 126 where every exactly-once
   serialization puts the counter at exactly 121. The run had ZERO
   `:info` ops (no 0-or-1 slack): per-key excess (max observed − `:ok`
   sum) of +46/+49/+40/+42/+49 ≈ 226 double-applied delta mass, while
   the cluster acknowledged 1895/1895 ops as clean successes. Exact
   configs quoted in the ledger.

**The L3 calibration takeaway**: the documented expiry boundary is
real, silent (the client sees only clean `:ok`s), and
**timeout-shaped, not crash-shaped** — process death at LAN latencies
cannot reach it; sustained ambiguity (quorum stalls, freezes — and by
extension slow fsync, the M4 territory) can. The Indeterminate-retry
rule's window arithmetic (client total retry span vs server expiry) is
now backed by an empirical conviction, and by three preserved negative
runs showing the default 60 s window holding with hundreds of live
retries.

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

### Criteria 2–4 — the gate matrix (final checker)

`env/run.sh test --workload counter --nemesis <kind> --time-limit 300`
(counter defaults: rate 1.4, 800 ops/key — no extra flags):

| Run | Exit | Wall | Analysis | ok / fail / info | Retries (ops) | Store (`20260806T…`) |
|---|---|---|---|---|---|---|
| counter+crash #1 | 0 | 313 s | 1.5 s | 2095 / 1 / 45 | **286** (109) | `…counter-crash/114845.009Z` |
| counter+crash #2 | 0 | 319 s | 1.4 s | 2043 / 2 / 30 | **197** (71) | `…counter-crash/115408.681Z` |
| counter+mixed-all | 0 | 315 s | 1.7 s | 2098 / 1 / 6 | **122** (86) | `…counter-mixed-all/115934.890Z` |
| Q14 red-by-design | **1** | 313 s | 1.6 s | 1895 / 0 / 0 | **304** (120) | `…counter-quorum-pause/114319.903Z` |
| register + seed-bug (regression) | **1** | 315 s | ~1 s | — | — | `…register-crash-seedbug-stale-reads/111830.517Z` |

- **Criterion 2**: both crash gates green with nonzero retry counts
  quoted (`:retry-evidence {:total 286, :ops 109, :by-f {:add 257,
  :read 29}}` and `{:total 197, :ops 71, …}`) — the
  retry-cache-across-failover proof at the default 60 s window;
  mixed-all green with 122 retries across all six fault kinds.
- **Criterion 3**: the Q14 conviction above; configs and verbatim
  violation quoted here and in the ledger; store preserved (plus the
  three negative-attempt stores).
- **Criterion 4**: the zero-retry evidence law is demonstrated by unit
  fixture (`retry-verdict-decision`: a required run with `:total 0`
  fails `:no-retry-evidence` — the distinct error), as the brief
  allows; every live dedicated run had nonzero retries.
- Register no-regression: the M0/M1 seeded-red still convicts on all
  five keys (`:failures [0 1 2 3 4]`, exit 1).

### Criterion 5 — analysis times, `:info` sanity, ownership

Analysis 1.4–1.7 s everywhere (the counter checker is quadratic per
key in the worst case but tiny in practice). `:info` sanity: the
shakedown measured 70-of-71 write `:info`s inside kill windows; the
final gates carry 45/30/6/0. The Q14 run's zero `:info` is itself
load-bearing (no slack — every excess unit a proven double).
Apache-2.0 headers on the two new files
(`ServerOptionsRetryCacheTest`, `workload/counter.clj`). `git diff
main`: `sut/**` (the two itemized pieces), `harness/**`, the two
itemized workflow hunks, `docs/RUNS.md` append, this report — inside
the ownership grant.

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
3. **A nemesis kind was added** (`quorum-pause`) though the brief's
   deliverables named none: three preserved attempts showed the Q14
   boundary is unreachable through kill or minority-pause (the
   crash-vs-timeout finding above), and the quorum stall is the
   smallest fault that produces the documented hazard's precondition.
   Pinned cycle, excluded from `mixed-all` (it is a Q14 lever, not
   general fault soup), full vocabulary/liveness-gating/test coverage.
4. **The green gates were re-run after the checker gained the
   observed-total rule**, so every quoted verdict comes from the
   shipped checker; the earlier same-day greens (weaker checker) are
   retained in the ledger as history.

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
- **The Q14 conviction margin depends on `:info` slack** in
  principle; the actual conviction run had ZERO `:info` ops (every
  stalled op resolved via retry), so every excess unit was a proven
  double — the cleanest possible margin.
- **The final-read phase rarely executes under the default budget**:
  4000 ops at ~14/s ≈ 285 s, so the 300 s time limit usually cuts
  before the quiesce+finals (`:final-read nil` in the gates). The
  observed-total rule makes this immaterial for conviction power, but
  a shorter default budget would restore the designed final
  tightening.

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
