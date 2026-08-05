# Job 05 report — nemesis breadth + liveness checking (M1, harness side)

## Summary

The harness now speaks four fault dialects instead of one: the Job 04
partition (unchanged), crash-restart (`kill -9` + restart of a
leader-biased random minority), pause (SIGSTOP/SIGCONT of a random
minority), and `mixed`, which interleaves whole fault cycles of the three
kinds at equal weight. A new liveness checker flags any run in which a
fault-free-majority window of 60 s saw clients continuously attempting
operations and receiving zero `:ok`s — the RATIS-2523-class
stuck-but-not-inconsistent state that linearizability checking grades as
merely slow. All green/red gate runs passed (crash ×2, pause, mixed
green; seeded-red under crash caught on all five keys). The two decisions
a reviewer should look hardest at: (1) the liveness checker's
"invocations attempted" condition is implemented as *continuous*
attempting (successive attempts ≤ 10 s apart), because the literal
≥-one-invocation reading false-positives every normal run's
post-op-budget idle tail; (2) elle is **not** adopted — `elle.rw-register`
hard-rejects our CAS-hit-rate-driven duplicate write values by design
(evidence below), so knossos-per-key stays primary and the workload shape
elle needs is recorded as an M2+ input.

## What was built

| File | One line |
|---|---|
| `harness/src/ratis_jepsen/nemesis.clj` | rewritten: unique fault vocabulary (`:start/:stop` partition unchanged; `:crash/:restart`; `:pause/:resume`), one composed nemesis routing all kinds, self-contained calm→fault→heal segments, leader-biased minority selection for crash, unbiased for pause, `mixed` = uniform random whole-segment interleave, CLI-configurable crash/pause cycles |
| `harness/src/ratis_jepsen/db.clj` | + `jepsen.db/Pause` (SIGSTOP/SIGCONT by pidfile, tolerant of missing/stale pidfiles), + best-effort leader census (last `changes role … to LEADER` log line per node — the `validate.sh` check (b) convention) |
| `harness/src/ratis_jepsen/checker.clj` | new: the liveness checker — nemesis-aware gating from history events (fault-op invocation opens a window; heal-op completion + 15 s grace closes it), stall = ≥ 60 s of calm with zero client `:ok` while attempts ran ≤ 10 s apart |
| `harness/src/ratis_jepsen/workload/register.clj` | `:liveness` joins the whole-history checker composition |
| `harness/src/ratis_jepsen/core.clj` | `--nemesis none|partition|crash|pause|mixed`, cycle knobs `--crash-calm-s`/`--crash-fault-s`/`--pause-calm-s`/`--pause-fault-s`, docstring refresh |
| `harness/test/ratis_jepsen/checker_test.clj` | new: the three mandated fabricated-history scenarios + edge cases + interval-arithmetic tests (12 tests) |
| `harness/test/ratis_jepsen/nemesis_test.clj` | new: kinds surface, vocabulary, minority sizing, leader-biased selection, segment shapes (Job 04 partition cycle pinned verbatim), configurable cycles, mixed interleave (9 tests) |
| `harness/test/ratis_jepsen/db_test.clj` | + leader-transition line classification |
| `docs/RUNS.md` | + M1 ledger entries: crash ×2, pause, mixed green; crash seeded-red |
| `jobs/05-nemesis-breadth/05_report.md` | this report |

### Design notes (the "your call, documented" items)

**Fault vocabulary and composition.** Every nemesis op `:f` is unique
across kinds (`:crash/:restart`, `:pause/:resume`; partition keeps Job
04's `:start/:stop` verbatim), so one `jepsen.nemesis/compose`d nemesis
serves every mode, history event names never depend on which `--nemesis`
was chosen, and the liveness checker's gating vocabulary is a single map
(`nemesis/fault->heal`). In partition-only mode the composed nemesis is
op-for-op identical to Job 04's bare partitioner.

**Segments.** Each fault kind is a self-contained generator segment —
calm sleep, fault op, fault-window sleep, heal op — so runs open calm
(Job 04 convention), faults never overlap, and in `mixed` a pause can
never land on a node the previous crash left dead. `mixed` draws whole
segments uniformly at random; kills-while-partitioned and similar
compound faults are deliberately out (M2 material, and they'd invalidate
the pause-never-hits-dead-node invariant).

**Crash targeting.** Size 1..2 uniform (max = n − majority(n), so
survivors always hold quorum), leader forced into the set with
probability 0.5 via the census. The census greps each node's log for its
last role transition over ssh — the same convention `env/validate.sh`
check (b) uses — and is explicitly best-effort: a stale answer (e.g. a
just-killed ex-leader that never logged a demotion) only weakens the
bias, never correctness. Census failure degrades to unbiased selection
with a warning. Heals act on every node (`start!`/`resume!` are no-ops on
running nodes), and restart reuses `db.clj`'s `start!*` unchanged — the
SUT opens storage with `StartupOption.RECOVER` on first boot and restart
alike.

**Liveness checker** (`ratis-jepsen.checker`, its own namespace — the
brief offered nemesis-ns co-location; a checker consulted by the workload
belongs outside the nemesis). Gating: a fault is active from its fault
op's *invocation* (conservative — the fault begins somewhere between
invocation and completion) until its heal op's *completion* (a heal is
only trusted once done), + 15 s grace. Pairing invocations with
completions needs no metadata: the nemesis is a single sequential
process, so occurrences of a given `:f` strictly alternate; an unhealed
(or un-completed-heal) fault gates through history end. Within the calm
complement, a violation is a ≥ 60 s stretch with zero client `:ok` in
which attempts ran continuously (successive invocations ≤ 10 s apart —
twice the harness invocation timeout, so workers cycling through
timed-out ops chain). See Deviations 1 for why "continuously" and not
"at least one invocation".

## How it was verified

All commands from the repo root (harness commands from `harness/`).
Versions throughout: ratis 3.2.2, jepsen 0.3.13, SUT
`ratis-kv 0.1.0-SNAPSHOT`, JDK 21, Clojure CLI 1.12.

### Criterion 1 — `clojure -M:test` green, new suites included, no regression

```
$ cd harness && clojure -M:test
...
Ran 53 tests containing 622 assertions.
0 failures, 0 errors.        (exit 0)
```

Jobs 03/04 left the suite at 31 tests / 252 assertions; the 22 new tests
are `ratis-jepsen.checker-test` (12 — named per the brief's scenarios:
`calm-window-stall-is-flagged`,
`stall-during-or-just-after-fault-is-not-flagged`,
`idle-generator-window-is-not-flagged`, plus
`stall-persisting-past-grace-is-flagged`,
`sparse-attempts-do-not-count-as-continuous`,
`info-completions-are-not-progress`,
`unhealed-fault-gates-through-history-end`,
`heal-invocation-without-completion-keeps-the-gate-closed`, …),
`ratis-jepsen.nemesis-test` (9), and
`leader-transition-line-classification` in `db-test`. All pre-existing
tests unchanged and green.

### Criterion 2 — green runs: crash ×2, pause, mixed (300 s each)

`env/run.sh up` once, then `env/run.sh test --nemesis <kind>
--time-limit 300` per run; every run `:valid? true`, exit 0,
"Everything looks good!", liveness checker composed in and valid:

| Run | Exit | Wall | knossos analysis | ok / fail / info | Store |
|---|---|---|---|---|---|
| crash #1 | 0 | 328 s | 5.4 s | 1057 / 408 / 35 | `ratis-kv-register-crash/20260805T044717.351Z` |
| crash #2 | 0 | 322 s | 2.1 s | 1036 / 439 / 25 | `ratis-kv-register-crash/20260805T045245.825Z` |
| pause | 0 | 322 s | 0.7 s | 1098 / 402 / 0 | `ratis-kv-register-pause/20260805T045807.172Z` |
| mixed | 0 | 326 s | 1.7 s | 1068 / 432 / 0 | `ratis-kv-register-mixed/20260805T050331.288Z` |

(1500 ops each: 5 keys × 300. Analysis times from the jepsen.log
`Analyzing…`/`Analysis complete` pair, e.g. crash #1
`04:52:33,068 → 04:52:38,427`.) Crash runs: ten kill cycles each,
killed nodes `:started` on restart vs `:already-running` for the rest;
leader-bias observed working (e.g. in the pre-matrix shakedown run the
then-current leader was killed in both cycles — n3 at term 1, then the
new leader n1 at term 2). Pause run: ten SIGSTOP cycles, all outcomes
definite (see the ledger for the term-2 election the final leader-pause
produced). Mixed run: ten whole segments, drawn 5 partition / 4 pause /
1 crash this run — uniform draws, small n. Per-run detail lives in
`docs/RUNS.md`.

### Criterion 3 — seeded-red under crash

```
$ env/run.sh test --nemesis crash --time-limit 300 --seed-bug stale-reads
...
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
exit 1        (333 s wall)
```

`:valid? false` with `:failures [0 1 2 3 4]` — every key convicted —
while ten kill cycles land and restarted nodes rejoin with the bug still
active (the `*** SEEDED BUG ACTIVE: stale-reads ***` banner reappears in
every node's log after every restart: the flag rides the same `db.clj`
start path the crash nemesis reuses). Key 0's violating pair, verbatim
(process 0 wrote 2, then read the ~500 ms-stale 3):

```
{:op {:process 0, :type :ok, :f :write, :value 2, :index 105, :time 4396595457}}
{:op {:process 0, :type :ok, :f :read,  :value 3, :index 116, :time 4527382074},
 :model #knossos.model.Inconsistent{:msg "can't read 3 from register 2"}}
```

Store: `ratis-kv-register-crash-seedbug-stale-reads/20260805T050901.890Z`.
(Stale reads still *acknowledge*, so the liveness checker rightly stays
valid; linearizability convicts.)

### Criterion 4 — outcome mapping under crash (`:info` vs kill windows)

Method (same as Job 04, now against crash windows): a window opens at a
`:crash` op's invocation and closes at its `:restart` op's completion;
each client `:info` completion is inside a window, adjacent (≤ 5 s — one
harness invocation timeout — after close), or outside. Analysis script
output over the two green crash runs:

| Run | `:info` total | inside | adjacent ≤5 s | outside |
|---|---|---|---|---|
| crash #1 | 35 | **35** | 0 | 0 |
| crash #2 | 25 | **25** | 0 | 0 |

```
fault windows: [[:crash 20.44 30.84] [:crash 50.89 62.67] ... [:crash 294.91 nil]]
client :info completions: 35 -> {:inside 35}
```

Calm phases are completely quiet; reads carry zero `:info` in every run
(the outcome map forbids it). The liveness checker's reported
`:calm-regions-s` for the same runs mirror these windows from the other
side (e.g. `[[0.438 20.444] [45.839 50.889] …]` — fault start to heal
completion + 15 s grace excluded).

### Criterion 5 — fabricated-stall demonstration

A doctored quiet-cluster history — healthy progress for 10 s, then 70 s
of clients attempting every second with zero acks, no nemesis anywhere —
fed straight to the checker core:

```
$ clojure -Sdeps '{:paths ["src" "test"]}' -M -e "...history as in
  checker-test/calm-window-stall-is-flagged...
  (pprint (c/check-liveness stall-history c/default-opts))"
{:valid? false,
 :window-s 60,
 :grace-s 15,
 :max-attempt-gap-s 10,
 :calm-regions-s [[0.0 79.1]],
 :violations
 [{:stall-start-s 10.0,
   :stall-end-s 79.1,
   :duration-s 69.1,
   :attempts 70}]}
```

The same history is `checker-test/calm-window-stall-is-flagged`; its
non-flagging siblings (`stall-during-or-just-after-fault…`,
`idle-generator-window…`) run in the same suite (criterion 1).

### Criterion 6 — elle decision: **not adopted; knossos stays primary**

`elle.rw-register`'s contract (its own ns docstring): *"Writes are
assumed to be unique, but this is the only constraint."* Duplicates are
a hard error by design — `wr-graph` throws (elle 0.2.7, the version
jepsen 0.3.13 ships): *"if there's more than one [writer of a value] we
can't do this sort of cycle analysis … users will want a big flashing
warning if they mess this up."* Our register workload writes
`rand-int 5` values precisely so CAS preconditions hit often (DESIGN
2.5); every key duplicates every value many times per run.

Empirically (translator: `:read ok [k v]` → `[[:r k v]]`, `:write ok` →
`[[:w k v]]`, `:cas ok [k [o n]]` → `[[:r k o] [:w k n]]`, cas
precondition-miss with known `:current` → `[[:r k cur]]`; script quoted
in full at the end of this report):

- **A real crash-run history** (the criterion-2 reference run, 1465
  completed client ops):

  ```
  === real run history: …ratis-kv-register-crash/20260805T044717.351Z/history.edn ===
  completed client ops: 1465 by f: {:write 490, :read 515, :cas 460}
  check THREW: java.lang.IllegalArgumentException
  message: Key 4 had value 2 written by more than one op: ({:index 1364, …
  ```

- **A synthetic history in our workload's exact value shape** fails the
  same way (`Key 0 had value 3 written by more than one op:` followed by
  23 ops).

- **The identical op mix with globally-unique write values checks
  clean** — `{:valid? true}` under
  `{:consistency-models [:strict-serializable], :linearizable-keys? true}` —
  including CAS encoded as `[[:r k old] [:w k new]]` transactions. CAS
  is *expressible* in elle's model; the misfit is exclusively our
  duplicate-value scheme.

Decision, with the workload shape elle would need recorded as the M2+
input (also in Suggestions): per-key-unique write values, CAS made
read-informed (read-then-CAS) or excluded from the elle-checked mix
(blind CAS against unique values never succeeds), op budget raised
10–100× — at which point elle removes the knossos memory cliff. Until
that workload exists, knossos-per-key remains primary and correct.

### Criterion 7 — headers, ownership, report

Apache-2.0 headers on all three new `.clj` files; all modified files
keep theirs. `git diff origin/main --stat`: 8 files, all `harness/**`,
plus `docs/RUNS.md` (append) and this report — exactly the brief's
ownership list.

## Deviations from the brief

1. **"Zero `:ok` operations while invocations were attempted" is
   implemented as *continuously* attempted** (successive attempts ≤
   `:max-attempt-gap-s` = 10 s apart, window extendable one gap past the
   last attempt). The literal ≥-one-invocation reading convicts every
   normal register run: the op budget exhausts minutes before
   `--time-limit`, so each run ends with a few trailing completions and
   then minutes of client silence — a >60 s "window containing an
   invocation with no `:ok`s" exists in every green history. The
   continuity rule separates "clients kept asking a healthy cluster and
   got nothing" (flagged; unit-tested) from "clients stopped asking"
   (exempt; unit-tested). The brief's three mandated scenarios hold under
   this reading.
2. **The crash/pause cycles are configurable via four plain CLI flags**
   (`--crash-calm-s`, `--crash-fault-s`, `--pause-calm-s`,
   `--pause-fault-s`) rather than a structured cycle spec. Smallest
   mechanism that satisfies "configurable cycle (default: …)"; the
   partition cycle is deliberately *not* configurable ("keep the Job 04
   partition behavior unchanged").
3. **elle is not adopted** — sanctioned by the brief ("if CAS ops don't
   fit elle's inference model cleanly, do not force it"), but recorded as
   a deviation from DESIGN's "elle replaces knossos as primary in M1"
   line. Evidence under criterion 6.

## Known gaps and risks

- **With default cycles, the liveness checker cannot fire during a
  fault-bearing run's fault phases.** Gating follows the brief's letter —
  *any* active fault (plus 15 s grace) closes the window — and the
  default cycles (30 s periods) never leave a 60 s calm stretch, so in
  crash/pause/mixed runs the checker guards only run tails and
  `--nemesis none` runs guard fully. A cluster that wedges *across* fault
  cycles (the true RATIS-2523 shape: healthy majority, zero progress,
  forever) would today be gated out in a default-cycle crash run. Two
  mitigations exist now (stretch the cycle: `--crash-calm-s 90` gives
  75 s-grace-adjusted calm ≥ T; or run the same SUT state under
  `--nemesis none`), and the real fix is a follow-up: gate on
  *majority-affecting* faults only — a minority crash/pause leaves a
  healthy majority and arguably should not close the window at all.
  Suggested below; it widens sensitivity without touching the brief's
  false-positive concerns (elections after a minority leader-kill resolve
  in ~2–5 s, far under T = 60 s).
- **The leader census reads node logs, not live state.** A node killed
  before logging a demotion still reads as leader until it rejoins and
  logs a transition; mid-election the census may read 0 or 2 leaders.
  Acceptable for a 0.5-probability bias (and observed working: both
  smoke-run kill cycles hit the then-current leader); anything
  correctness-relevant must not reuse it as-is.
- **`:restart` does not await the startup line.** The nemesis restarts
  nodes fire-and-forget (awaiting would misread the *previous* boot's
  startup line, since the log persists across restarts). A node that
  fails to boot surfaces as degraded capacity in the next fault cycle's
  ops, the checker stack, and collected logs — loudly enough in practice
  (observed: `:started` results and rejoin transitions in every cycle).
- **Pause windows overlap client timeouts.** A SIGSTOPped node holds
  established client connections silently; those ops burn the full 5 s
  harness timeout and land `:info` (writes) / `:fail` (reads). Expected
  and observed clustering inside fault windows only.
- **knossos remains the analysis-cost cliff** (unchanged from Job 04);
  the elle migration that would remove it now has a concrete workload
  prerequisite (unique writes) recorded for M2+.

## Suggestions (out of scope)

- **Majority-aware liveness gating (M2):** gate the liveness window only
  on faults that can remove the healthy majority (partitions; any fault
  set touching ≥ majority nodes), letting minority crash/pause windows
  stay checkable. This turns the checker from a calm-phase guard into a
  live cross-cycle wedge detector under the default crash cycle.
- **elle-mode register workload (M2+):** per the criterion-6 evidence —
  per-key-unique write values (e.g. worker-id × 10⁶ + counter),
  read-then-CAS instead of blind CAS (or CAS excluded from the
  elle-checked mix), op budget raised 10–100×; keep the current
  CAS-bearing workload under knossos as the smoke tier.
- **Compound faults (M2):** kill-while-partitioned, pause-then-partition
  — requires relaxing the whole-segment invariant deliberately, with
  restart-awareness in the pause nemesis.
- **`checker/perf` nemesis shading:** wire `:nemeses` plot metadata so
  perf graphs shade crash/pause/partition windows; would have made the
  criterion-4 evidence one screenshot instead of a script.
- **Image additions (Job 06):** `gnuplot-nox` joins git/sudo on the
  image wishlist (this job side-loaded it for perf plots).

## Environment notes (this execution sandbox, not the repo)

Same class of sandbox as Job 04's session (loopback TLS-re-terminating
egress proxy; containers have no direct egress), handled the same way,
all uncommitted: a locally-tagged `ubuntu:24.04` shim (proxy CA baked in,
apt sources switched to https) so the unmodified `env/Dockerfile` builds;
`RJ_EXTRA_CA_BUNDLE` + `RJ_DOCKER_BUILD_ARGS="--network host
--build-arg https_proxy=…"` (both Job 02's own knobs); control's
`/root/.m2` seeded from the host's Maven cache; gnuplot side-loaded into
control from host-downloaded debs for perf plots. The SUT tarball was
built on the host with the module's own `mvnw`.

## Appendix — the elle experiment script (criterion 6)

Run as `clojure -J-Djava.awt.headless=true
[-J-Drj.history=<store>/history.edn] -M -e '(load-file "...")'` from
`harness/` (elle 0.2.7 is already on the classpath as jepsen's own
dependency; the headless flag stops elle's rhizome dependency from
touching AWT at load time). Not committed — the deliverable is this
decision, not an elle integration.

```clojure
(require '[elle.rw-register :as rw]
         '[jepsen.history :as h])

;; our register ops -> elle rw-register micro-op txns
(defn reg-op->txn [{:keys [f type value current]}]
  (let [[k v] value]
    (case f
      :read  (when (= type :ok) [[:r k v]])
      :write (when (= type :ok) [[:w k v]])
      :cas   (let [[o n] v]
               (cond (= type :ok) [[:r k o] [:w k n]]
                     (and (= type :fail) current) [[:r k current]]
                     :else nil)))))

(defn ->elle-history [reg-ops]
  (->> reg-ops
       (keep (fn [op]
               (when-let [txn (reg-op->txn op)]
                 (assoc (select-keys op [:process :type :time])
                        :f :txn :value txn))))
       (map-indexed (fn [i op] (assoc op :index i)))
       vec
       h/history))

;; real history: completed client ops only
(let [path (System/getProperty "rj.history")
      ops  (with-open [r (java.io.PushbackReader. (clojure.java.io/reader path))]
             (->> (repeatedly #(clojure.edn/read {:eof ::eof} r))
                  (take-while #(not= ::eof %))
                  (filter #(and (integer? (:process %))
                                (contains? #{:ok :fail} (:type %))))
                  vec))]
  (rw/check {:consistency-models [:strict-serializable]
             :linearizable-keys? true}
            (->elle-history ops)))
```
