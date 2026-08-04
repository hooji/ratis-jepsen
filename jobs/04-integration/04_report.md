# Job 04 report — integration: register workload, partition nemesis, M0 exit gate

## Summary

M0 is closed: `env/run.sh test` is real, and the two reference runs exist —
a green run (register workload + random-halves partition, knossos
`:valid? true`, exit 0) and a red run where the harness convicts the SUT's
`--seed-bug stale-reads` mode on **all five keys** with a concrete
write-2-then-read-1 violation. Integration friction was real and is fully
documented below; `db.clj` itself ran correctly against real containers on
first contact (install, startup-line await, pidfile kill, wipe, log
collection — unchanged except the planned seed-bug arity). The two
decisions a reviewer should look hardest at: (1) the knossos budget — a
key history carrying ~40 partition-window `:info` ops OOMs the linear
checker at any reasonable heap, so concurrency is now spread across keys
(2 workers/key, keys in parallel; DESIGN §2.5's "5 keys, ≤400 ops/key,
concurrency 10" all still hold) and `--ops-per-key` defaults to 300 via
the brief's sanctioned shrink clause; (2) `env/run.sh test` carries two
tiny stand-ins (`git`, `sudo`) because jepsen hard-requires both binaries
and the Job-02 image ships neither — flagged for the coordinator as image
follow-ups, no-ops once the image grows the real tools.

## What was built

| File | One line |
|---|---|
| `harness/src/ratis_jepsen/workload/register.clj` | r/w/cas mix over independent keys, per-key `gen/limit` hard cap, 2 workers/key in parallel, per-key knossos cas-register + timeline, stats + unhandled-exceptions, perf when gnuplot exists |
| `harness/src/ratis_jepsen/nemesis.clj` | `none` \| `partition` seam; `partition-random-halves` on a 15 s on / 15 s off cycle |
| `harness/src/ratis_jepsen/core.clj` | real CLI: `--workload`, `--nemesis`, `--key-count`, `--ops-per-key`, `--seed-bug`, `--store-dir`, and budget defaults for `--concurrency` (10) / `--time-limit` (300) |
| `harness/src/ratis_jepsen/db.clj` | `--seed-bug` appended to the start command (record field → `server-args`) |
| `harness/deps.edn` | + `ratis-metrics-default` (client hard-requires an impl at runtime); `:run` gets `-Xmx10g` for knossos |
| `harness/test/ratis_jepsen/db_test.clj` | `server-args` seed-bug arity + coverage |
| `env/run.sh` | `test` body only: tarball ensure (build in control if absent), git + sudo stand-ins, harness invocation vs `n1..n5`, `store/` on the bind mount, exit-code propagation |
| `docs/RUNS.md` | the run ledger: reference green + red entries |
| `jobs/04-integration/04_report.md` | this report |

## How it was verified

All commands from the repo root. Versions throughout: ratis 3.2.2, jepsen
0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`, JDK 21, Clojure CLI 1.12.

### Criterion 1 — reference GREEN

```
$ env/run.sh up          # image build + 8 containers + ssh-ready checks
run.sh: all 7 nodes ssh-ready
$ env/run.sh test --nemesis partition --time-limit 300
...
Everything looks good! ヽ(‘ー`)ノ
GREEN RUN EXIT: 0  WALL: 313s
```

Checker summary (store `ratis-kv-register-partition/20260804T172534.329Z`):

```
:independent {... :valid? true ... :failures []}
:stats {:valid? true, :count 1500, :ok-count 1072, :fail-count 428, :info-count 0, ...}
:exceptions {:valid? true}
:perf {:latency-graph {:valid? true}, :rate-graph {:valid? true}, :valid? true}
```

1500 ops (5 keys × 300), every per-key linearizability analysis valid
under ten 15 s partition windows. The 428 fails are definite outcomes:
CAS precondition misses (by design), plus election-transient
`:not-leader-or-not-ready` / `:read-index` classifications.

### Criterion 2 — reference RED

```
$ env/run.sh test --nemesis partition --time-limit 300 --seed-bug stale-reads
...
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
RED RUN EXIT: 1  WALL: 313s
```

`:valid? false` with `:failures [0 1 2 3 4]` — every key convicted. The
nodes' collected logs prove the flag plumbed through `db.clj`:

```
2026-08-04 17:31:45.419 [n1-impl-thread1] WARN ... - *** SEEDED BUG ACTIVE: stale-reads ***
```

Concrete violating pair from key 0's analysis (process 0 wrote 2, then
read the ~500 ms-stale 1):

```
{:op {:process 0, :type :ok, :f :write, :value 2, :index 228, :time 4337901282}}
{:op {:process 0, :type :ok, :f :read,  :value 1, :index 233, :time 4362177750},
 :model #knossos.model.Inconsistent{:msg "can't read 1 from register 2"}}
```

### Criterion 3 — outcome-mapping sanity (`:info` vs nemesis windows)

Method: from `history.edn`, partition windows are `[start-op time ..
last stop-op time]` per nemesis cycle (a window opens at the `:f :start`
invoke and closes at the `:f :stop` completion — when iptables heal);
each client `:info` completion is then inside a window, adjacent
(within ±5 s — one harness invocation timeout), or outside. The reference
green run completed with **zero `:info` ops at all** (its op-phase
partition windows kept the leader majority-side, so every transient was a
definite `:fail`), which satisfies the criterion trivially; two archived
sibling runs of the identical command *did* catch leader-in-minority
windows and give the real evidence:

| Run (store id) | `:info` total | inside | adjacent ±5 s | outside |
|---|---|---|---|---|
| green reference `…T172534Z` | 0 | 0 | 0 | 0 |
| sibling `…T170652Z` | 60 | 41 | 17 | 2 |
| sibling `…T171457Z` | 71 | 51 | 17 | 3 |

The five "outside" ops are all `:timeout` completions landing 5.5–6.5 s
after a heal (t=155.5 s/155.8 s vs window ending 150 s; t=36.1–36.5 s vs
window ending 30 s) — ops issued onto still-recovering connections just
past the grace cutoff, not calm-phase noise. No `:info` appears anywhere
else in any run; the calm `--nemesis none` runs have `info-count 0`.

### Criterion 4 — knossos stays bounded

The hard cap lives in the generator
(`harness/src/ratis_jepsen/workload/register.clj`):

```clojure
(->> (gen/mix [r w cas])
     (gen/stagger 1/10)
     ;; THE hard cap (DESIGN 6: "hard-cap in the generator, not in
     ;; prose") — bounds each per-key history knossos must check.
     (gen/limit ops-per-key))
```

Analysis wall-clock, from the runs' logs: green **0.4 s**
(`17:30:42,238 Analyzing… → 17:30:42,635 Analysis complete`), red
**0.5 s**. Two earlier shapes of the same budget did *not* stay bounded
and drove design changes (documented in Deviations 1–2): with all ten
workers on one key at a time, a key catching two leader-in-minority
windows accumulated ~40 crashed-op (`:info`) entries and the linear
checker went `:cause :out-of-memory` even at a 10 GB heap. The shipped
shape (2 workers/key, keys parallel, 300 ops/key default) cuts per-key
pending ops ~5× and checks in sub-second time.

### Criterion 5 — calm cluster manufactures no failures

Re-run on the final code:

```
$ env/run.sh test --nemesis none --time-limit 60
Everything looks good! ヽ(‘ー`)ノ
CALM RUN EXIT: 0  WALL: 50s
:stats {:valid? true, :count 1500, :ok-count 1063, :fail-count 437, :info-count 0,
        :by-f {:cas   {... :ok-count  91, :fail-count 425 ...}    ; precondition misses
               :read  {... :ok-count 481, :fail-count   3 ...}    ; election transients
               :write {... :ok-count 491, :fail-count   9 ...}}}
```

Zero `:info`, zero unknown-throwable logs; every fail is a designed CAS
precondition miss or a boot-election transient. (This run also exercises
the perf guard: control had no gnuplot at that point, the workload logged
`gnuplot not found — perf plots disabled` and stayed green.)

### Criterion 6 — clean teardown, clean diff

```
$ env/run.sh down 2>&1 | tail -1
 Network ratis-jepsen_jepsen Removed
$ docker ps --format '{{.Names}}' | wc -l
0
$ git status --short          # after all runs
(empty — store/, sut/ratis-kv/target/, env/.state/ all gitignored)
```

The PR diff contains no `store/` or `target/` content.

### Criterion 7 — headers, ownership, report

Apache-2.0 headers on both new `.clj` files (and all modified files keep
theirs). Files touched, per `git diff --stat` against `main`:
`harness/**` (7 files), `env/run.sh` (the `cmd_test` body between its
markers only — the diff context shows the stub lines replaced and nothing
else in the file), `docs/RUNS.md` (new, granted), and this report.

`clojure -M:test` after all changes: `Ran 31 tests containing 252
assertions. 0 failures, 0 errors.`

## Integration friction found and fixed (each per the brief's mandate)

1. **Harness classpath lacked the Ratis metrics impl.** First cluster run:
   every op failed `NoClassDefFoundError: o.a.r.metrics.RatisMetrics`;
   with `ratis-metrics-api` added, `MetricRegistries.global()` then threw
   `ExceptionInInitializerError` — the service-loaded *impl* is a hard
   client-side requirement at 3.2.2, not a no-op-able option. Fix:
   `ratis-metrics-default 3.2.2` in `deps.edn`. (Never seen in Job 03
   because only the `:test` alias — which carries the SUT jar — had
   exercised a live `RaftClient`.)
2. **jepsen `run!` dies when `git` is missing outright** (it shells out
   for provenance logging and handles nonzero exits but not a missing
   binary); the image has no git. Fix: `run.sh test` installs an
   always-exits-1 `git` stand-in on control (no-op once the image has
   git).
3. **jepsen's iptables partition path wraps node commands in
   `sudo -k -S -u root bash -c …`**; the image has no sudo (ssh lands as
   root). Fix: a per-node `sudo` stand-in that swallows sudo's flags and
   execs the command. The first version consumed a password line from
   stdin and deadlocked every wrapped command (sshj holds channel stdin
   open; jepsen's `su` path sends no password) — the shipped version
   leaves stdin untouched.
4. **knossos OOM** (criterion 4 above): fixed by spreading concurrency
   across keys, shrinking the ops-per-key default, and `-Xmx10g` on the
   `:run` alias.
5. **The perf checker errors the whole analysis to `:unknown` when
   gnuplot is missing**; the image has none. Fix: perf joins the checker
   composition only when a `gnuplot` binary is present, with a loud
   warning otherwise. (The reference green/red runs had gnuplot available
   and include the full composition with valid perf graphs.)

`db.clj` needed **no** container-driven fixes: tarball glob, upload,
untar, `start-stop-daemon` start, startup-line await, `kill -9` by
pidfile, wipe and log collection all worked as written in Job 03.

## Deviations from the brief

1. **`--ops-per-key` defaults to 300, not 400.** A 400-op key history
   carrying ~40 partition `:info` ops is beyond the linear checker at any
   sane heap. Shrunk via acceptance criterion 4's own clause ("shrink
   ops-per-key and say so"); DESIGN §2.5 pins only *≤*400, and the flag
   still accepts 400.
2. **Concurrency is spread across keys (2 workers/key, 5 keys in
   parallel)** rather than all ten workers walking one key at a time. The
   brief/DESIGN fix 5 keys, ≤400 ops/key, concurrency 10 — all honored;
   what changed is only which workers hit which key when. This is what
   makes knossos tractable (pending crashed ops per key divide by five)
   and it shortens runs.
3. **`checker/perf` is conditional on gnuplot** (friction item 5). The
   brief lists it unconditionally; unconditional inclusion turns a
   missing plotting binary into an `:unknown` verdict for otherwise-valid
   runs, which is strictly worse than a loud warning.
4. **`run.sh test` contains the git/sudo stand-ins** beyond the plain
   harness invocation the brief describes. Both are required for jepsen
   to run at all against the Job-02 image, both live inside the sanctioned
   `cmd_test` markers, and both are no-ops once the image ships the real
   binaries (suggested below).
5. **Two CLI options beyond the brief's list**: `--store-dir` (how
   `run.sh test` lands results at `/ratis-jepsen/store/` while the
   harness stays Docker-ignorant) and the `:run` alias's `-Xmx10g`
   (knossos heap). Also `--nemesis` defaults to `none`, so the brief's
   green command names `partition` explicitly.
6. **The reference green run's own `:info` count is zero**, so criterion
   3's inside/outside evidence leans on two archived sibling runs of the
   identical command that did catch leader-in-minority windows (table
   above). Nothing was tuned to make the reference run quiet — it is the
   luck of random-halves leader placement, and the siblings demonstrate
   the mapping under stress.

## Environment notes (this execution sandbox, not the repo)

This session's sandbox blocks direct outbound traffic (TLS-intercepting
CONNECT proxy on loopback; containers cannot reach it). To execute the
gate here, the following *uncommitted, sandbox-only* accommodations were
used; none are needed on a normally-networked host, and none are part of
the deliverable: a locally-tagged `ubuntu:24.04` shim with https apt
sources + the proxy CA (so the unmodified `env/Dockerfile` apt layer can
build), `RJ_DOCKER_BUILD_ARGS="--network host --build-arg https_proxy=…"`
plus `RJ_EXTRA_CA_BUNDLE` (both knobs are Job 02's own), seeding
control's `/root/.m2` from the host cache (containers have no egress for
Maven/Clojure downloads; on a normal host the first run downloads into
the compose volume per Job 02's README), and side-loading gnuplot's debs
into control for the reference runs. The in-control tarball build
fallback was verified against the seeded repo
(`mvnw … -q package` inside control produced a fresh tarball).

## Known gaps and risks

- **knossos remains a cliff, not a slope.** The shipped shape keeps
  per-key pending ops low (analysis in ~0.5 s), but a key unlucky enough
  to catch several leader-in-minority windows could still blow up the
  linear checker. DESIGN already plans the elle migration (M1) that
  removes the cliff; until then a rare `:unknown`-by-OOM rerun is
  possible.
- **Runs idle ~4 minutes after the op budget exhausts** (the finite
  workload generator ends while the infinite nemesis cycle keeps the test
  alive until `--time-limit`). Harmless, wastes wall-clock; suggestion
  below.
- **Image gaps compensated in code**: git, sudo, gnuplot (stand-ins /
  guard). If a future jepsen version shells out to something new, the
  same failure class recurs.
- **`checker/stats` grades a run `:unknown` if any `:f` never succeeds**
  — conceivable in very short `--time-limit` runs (a cas-heavy tail with
  no hit); not observed here (calm 60 s runs had 91+ cas `:ok`s).
- The `:info`-window analysis is a session script quoted in this report,
  not committed tooling; DESIGN §5.3's "eyeball" check deserves a real
  checker (suggestion below).

## Suggestions (out of scope)

- **Image (Job 02 follow-up)**: add `git`, `sudo`, and `gnuplot-nox` to
  `env/Dockerfile` — three packages that make jepsen's provenance
  logging, its iptables path, and its perf plots work without the
  `run.sh` stand-ins and the checker guard's degraded mode.
- **M1 elle migration** (already planned in DESIGN §2.5) — removes the
  knossos memory cliff and would let `--ops-per-key` return to 400+.
- **End runs at workload exhaustion**: give the nemesis generator a
  finite horizon (or wrap the composed generator so client exhaustion
  ends the test) to reclaim the idle tail.
- **Promote the `:info`-vs-nemesis-window analysis into a checker** that
  fails runs with `:info` bursts in calm phases — automates DESIGN §5.3.
- **Consider `gen/log`-style phase markers** or store-dir naming that
  distinguishes multiple runs of the same test name beyond timestamps
  (minor operator ergonomics).
