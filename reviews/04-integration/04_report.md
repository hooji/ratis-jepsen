# Review 04 report — Job 04: integration: register workload, partition nemesis, M0 exit gate

Worker PR: #8 (`claude/harness-core-brief-urftoi`, head `02a9878`, base
`9894617`). Reviewed in a detached worktree at the PR head; every run
below is mine, executed in that worktree against the unmodified Job-02
environment. Docker 29.3.1, x86_64, 4 cores / 15 GB RAM.

## Verdict: MERGE

## Justification

All seven acceptance criteria reproduce independently: the reference
GREEN run is `:valid? true` / exit 0 — twice, across two fresh
`up`-cycles, plus a calm and a shortened-limit run, all with zero
`:info` and zero manufactured failures — and the reference RED run is
`:valid? false` / exit 1 on **all five keys**, where I verified from the
raw history (not just knossos's word) that the convicted op is a genuine
~500 ms stale read. Seed-bug plumbing, generator caps, analysis
boundedness, exit-code propagation, teardown cleanliness, ownership,
headers and the Job-03 test suite all check out. The worker's five
integration-friction fixes and six deviations are each documented and
defensible. Findings are non-blocking (mostly latent env-image gaps
outside this job's ownership). M0's exit gate is real: this harness
demonstrably stays quiet on a healthy cluster and convicts a lying one.

## Verification environment

My sandbox routes egress through a TLS-inspecting proxy, so I used the
env's own knobs, plus two uncommitted sandbox-only accommodations of the
same class the worker documented for their sandbox — none touch the
deliverable:

- `RJ_EXTRA_CA_BUNDLE=<5 Anthropic egress CA certs>` and
  `RJ_DOCKER_BUILD_ARGS="--network host --build-arg https_proxy=…"`
  (both Job-02 knobs; the unmodified `Dockerfile` builds with them).
- Post-`up`, imported those CAs into control's JVM truststore — the
  image's ca-certificates-java hook silently imports **none** of a
  multi-cert bundle (Finding 1), so Maven/Clojure inside control failed
  PKIX while curl worked.
- Installed real `git` in control solely to run `clojure -M:test`
  (its `:test` alias has a git dep; Finding 3 — pre-existing, not this
  job's regression).

Containers otherwise had working (MITM'd, CA-verified) egress, so the
`run.sh test` flow — including the in-control tarball build fallback —
ran exactly as shipped.

## What I verified

### Criterion 1 — reference GREEN (run twice, fresh `up` between)

```
$ env/run.sh up          # image build + 8 containers, all 7 nodes ssh-ready
$ env/run.sh test --nemesis partition --time-limit 300
...
Everything looks good! ヽ(‘ー`)ノ
GREEN1_EXIT=0 WALL=364s     # includes the in-control mvnw tarball build
```

Run #1 (store `20260805T033207.584Z`): `:valid? true`, `:failures []`,
1500 ops = 1074 `:ok` / 426 `:fail` / **0 `:info`**; knossos analysis
0.66 s. The no-tarball path was exercised for real — `run.sh: no SUT
tarball; building inside control` ran `mvnw … -q package` (SUT's in-JVM
test suite included) before the harness started.

Run #2, after interrupt-probe + `down` + fresh `up` (store
`20260805T035300.171Z`): `:valid? true`, exit 0, 320 s wall,
1060/440/0, analysis 0.32 s. **The green gate is stable across cycles.**
Both runs match the `docs/RUNS.md` green entry (worker: 313 s,
1072/428/0, 0.4 s analysis) — no mismatch to flag.

### Criterion 2 — reference RED

```
$ env/run.sh test --nemesis partition --time-limit 300 --seed-bug stale-reads
...
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
RED_EXIT=1 WALL=315s
```

Store `20260805T034148.322Z`: `:valid? false`, `:failures [0 1 2 3 4]`
— every key convicted, 8 `Inconsistent` blocks across the analysis
(a cluster, not a single lucky catch — probe suggestion 3). Flag
plumbing verified on the live cluster: all five nodes' `/proc/<pid>/cmdline`
end `--seed-bug stale-reads`, and every node's log carries
`*** SEEDED BUG ACTIVE: stale-reads ***`; the green runs' cmdlines carry
no such flag (absent by default).

The violation is a genuine stale read. Knossos's key-0 conviction:

```
{:op {:process 0, :type :ok, :f :read, :value 0, :index 131, :time 2862715742},
 :model #knossos.model.CASRegister{:value 0}}
{:op {:process 1, :type :ok, :f :read, :value 1, :index 142, :time 2956781661},
 :model #knossos.model.Inconsistent{:msg "can't read 1 from register 0"}}
```

I re-derived it from the raw history, independent of the checker: writes
of `0` (idx 92) and `1` (idx 96) both complete by t=2.493 s; the only
subsequent write-path op, `cas [3 0]`, **fails at t=2.860 s reporting
`:current 1`** — the SUT's own reply pins the authoritative register at
`1`, and no other state-changing op completes until t≥3.064 s. Yet a
read invoked t=2.752 s completes t=2.863 s returning **`0`**, and reads
flip to `1` only at t≈2.96 s — i.e. ~500 ms behind write 1's apply at
~2.49 s, exactly the seeded shadow-map lag (`KvStateMachine` applies
shadow entries on a 500 ms delayed executor; `query` serves the shadow
when seeded). All convicted ops are completed `:ok` reads — not
timeouts, not outcome-mapping artifacts. Matches the RUNS.md red entry's
form (worker's run also convicted all five keys).

### Criterion 3 — outcome-mapping sanity (`:info` vs nemesis windows)

Window definition (recomputed with my own script, not the worker's): a
window opens at the nemesis `:f :start` invocation entry and closes at
the `:f :stop` network-healed completion entry; `:info` client
completions are inside, adjacent (±5 s), or outside. Every partition run
showed 10 windows of ~15.1 s (e.g. 15.2–30.3 s, 45.3–60.3 s, …) with
live-verified iptables DROP rules cycling on/off through the sudo shim.

Result across **all five** of my runs (2×green, red, calm,
120 s/400-ops probe): **zero client `:info` completions anywhere** —
7500 client ops total. Inside=0, adjacent=0, outside=0; there is no
calm-phase `:info` flood (the REVISE-grade defect this criterion
probes), and `:stats` agrees (`:info-count 0` in every run).

Two honest caveats. (a) With zero `:info`, "only during windows" holds
vacuously; the worker's non-vacuous distribution (60–71 `:info`, 96–97 %
inside/±5 s) comes from two archived sibling stores that are not in git
(by design — stores never enter git), so it is not reproducible
post-hoc. My five zero-`:info` runs corroborate their account that
zero-`:info` greens are the common case; the mechanism that would
produce windowed `:info` (client `TimeoutIOException` → `:info` for
writes under a minority-side leader) is verified in the Job-03 outcome
map, which this PR does not touch. (b) The reason `:info` is rare is
Finding 5: the op budget exhausts ~35 s into the run, so ops only ever
contest the first window or two.

### Criterion 4 — knossos stays bounded

The hard cap, quoted from
`harness/src/ratis_jepsen/workload/register.clj:66-69` (per-key
generator):

```clojure
(->> (gen/mix [r w cas])
     (gen/stagger 1/10)
     ;; THE hard cap (DESIGN 6: "hard-cap in the generator, not in
     ;; prose") — bounds each per-key history knossos must check.
     (gen/limit ops-per-key))
```

Total ops are bounded by construction: `independent/concurrent-generator`
walks the finite `(range key-count)`, so ≤ key-count × ops-per-key
(observed exactly 1500 = 5×300 default; 2000 = 5×400 in the probe).
Analysis wall-clock from `jepsen.log` `Analyzing…→Analysis complete`:
0.66 s (green 1), 0.32 s (green 2), 0.38 s (red), 0.71 s (probe at the
brief's original 400 ops/key) — far inside the ~5 min ceiling. The
worker's OOM claim (all-ten-workers-on-one-key + ~40 crashed ops) was
not counterfactually re-triggered; the shipped shape's boundedness is
what the criterion demands and is verified.

### Criterion 5 — calm cluster manufactures no failures

```
$ env/run.sh test --nemesis none --time-limit 60
Everything looks good! ヽ(‘ー`)ノ
CALM_EXIT=0 WALL=46s
```

`:valid? true`, 1500 ops, `:info-count 0` overall and per `:f`; every
fail is a designed CAS precondition miss or an election transient. The
run ends at generator exhaustion (46 s < 60 s), consistent with the
worker's 50 s.

My containers have no gnuplot, so all five runs also exercised the perf
guard the worker added: `WARN … gnuplot not found — perf plots disabled
for this run`, `:perf` omitted from the composition, run still green —
the degraded path works (the worker's reference runs exercised the
with-gnuplot path; between us both branches are covered).

### Criterion 6 — clean teardown, clean diff

`run.sh down` after the final run — and, harder, immediately after the
interrupt probe below — leaves `docker ps -a` with **0 containers**, 0
`ratis` volumes, network removed. `git status --short` in the worktree
is empty after all runs (store/, target/, .cpcache all ignored), and the
PR diff contains no `store/` or `target/` paths.

### Criterion 7 — headers, ownership, report

- Diff: 9 files, +684/−42 — all inside `harness/**`, the `cmd_test`
  body, `docs/RUNS.md`, and the job report. `git diff` on `env/` shows
  **one hunk**, entirely between the `# BEGIN/END Job-04` markers
  (marker text itself updated stub→test-body, which is the sanctioned
  replacement); `validate.sh`, Dockerfile, compose untouched; nothing
  under `sut/**`.
- Apache-2.0 headers on both new `.clj` files; modified files keep
  theirs.
- Worker report present with every section `jobs/README.md` requires,
  including the mandated per-fix documentation of integration friction.

### Job-03 suite regression check (review-brief emphasis 5)

```
control$ cd /ratis-jepsen/harness && clojure -M:test
Ran 31 tests containing 252 assertions.
0 failures, 0 errors.        # exit 0
```

252 = Job 03's 251 + the new `server-args` seed-bug assertion. `db.clj`
audited against DESIGN §2.6: install dir, storage dir, log path, raft
port, startup-line await, root ssh — all untouched; the sole change is
the planned seed-bug arity (record field → `server-args` `cond->`), and
the un-seeded path produces the identical argv (unit-tested, and
verified live in the green runs' cmdlines). The worker's claim that
`db.clj` needed zero container-driven fixes is consistent with the diff
and with five clean setup/teardown cycles I ran.

### Friction fixes and deviations, audited

1. `ratis-metrics-default` dep: present; all runs work with it. The
   necessity claim matches Ratis 3.2.2's service-loaded metrics design;
   not counterfactually re-verified (additive dep, no contract impact).
2. git stand-in: exercised in green #1/green #2 (fresh control has no
   git → stand-in installed → jepsen ran); with real git present (my
   later cycle-A runs) the guard no-ops as claimed. Both paths pass.
3. sudo shim: parses jepsen's `sudo -k -S -u root bash -c …` shape
   correctly (verified by reading + by live iptables DROP cycling and
   `[:isolated {…}]` nemesis completions); leaves stdin alone.
4. knossos budget reshape (2 workers/key, default 300 ops/key,
   `-Xmx10g`): DESIGN §2.5's pins (5 keys, ≤400 ops/key, concurrency 10)
   all still hold — `:concurrency 10` in the parsed test options, 2
   worker processes per key in the histories, cap enforced in code; the
   probe confirms `--ops-per-key 400` still works. Deviation documented
   under the brief's own shrink clause; accepted.
5. perf guard: verified both branches (above). Strictly better than an
   `:unknown` verdict on missing gnuplot.
6. Extra CLI opts (`--store-dir`, `-Xmx10g`, `--nemesis` defaulting to
   `none`): each justified in the report; `--store-dir` is what keeps
   the harness Docker-ignorant while landing `store/` on the bind mount
   (verified: results appear on the host, survive `down`).

### Probe (beyond the worker's testing)

Interrupt probe: killed `run.sh test` mid-run (SIGINT ×2 then SIGTERM;
exit 143). Notable: the harness JVM **keeps running inside control** —
`docker compose exec -T` does not proxy signals, so even a real Ctrl-C
only kills the host-side client (Finding 4). No wedged state results:
the next `down` removed everything (0 containers/volumes, the orphaned
harness dying with its container), a fresh `up` + full green run #2
succeeded, and the only residue is a partial store dir on the gitignored
bind mount. Second probe: `--time-limit 120 --ops-per-key 400` → green,
exit 0, 133 s wall, analysis 0.71 s — no budget assumptions hiding in
the defaults.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking (env, Job 02 scope) | `env/Dockerfile:41-51` | The "trusted system-wide, **including the JVM keystore**" claim fails for multi-cert `RJ_EXTRA_CA_BUNDLE` files: the ca-certificates-java hook imported 0 of my 5 certs (curl fine, Maven PKIX-fails inside control). README explicitly invites "just the extra CA(s)", plural. Split-and-import per cert, or import into the JVM store explicitly. |
| 2 | non-blocking | `env/run.sh:21` (+ `usage()` output) | `run.sh help` still says `test   stub until Job 04 lands the harness (exits 64)` — stale, but the line sits **outside** the sanctioned `cmd_test` markers, so the worker could not fix it without an ownership violation. Coordinator follow-up when `env/` ownership next opens. |
| 3 | non-blocking (pre-existing) | `harness/deps.edn:53-54` | `clojure -M:test` cannot run on a pristine control: the `:test` alias's cognitect test-runner is a **git dep** and the image ships no git (Job 03 ran the suite outside the container). The suite passes once git exists. The worker's suggested image follow-up (add git) fixes this too. |
| 4 | non-blocking | `env/run.sh:153-158` | Interrupting `run.sh test` (Ctrl-C/kill) leaves the harness running inside control to completion — `docker compose exec -T` doesn't forward signals. Recovery is clean (`down` kills it; fresh `up`+green verified), so this is operator ergonomics, not a defect. |
| 5 | non-blocking (design-level) | `harness/src/ratis_jepsen/workload/register.clj:59-69` | Thin fault overlap: at ~10 ops/s/worker the 1500-op budget exhausts ~35 s into a 300 s run, so client ops contest only ~1–2 of the 10 partition windows; the remaining ~4 min of nemesis cycles fault an idle cluster. This bounds the green gate's evidentiary strength (and explains why zero-`:info` greens are the norm — 5/5 of my runs). Numbers are brief/DESIGN-pinned, and the worker already flags the idle tail; fold "make windows contested" into the M1 elle/liveness work. |
| 6 | non-blocking | `jobs/04-integration/04_report.md` (criterion 3) | The non-vacuous `:info`-distribution evidence lives only in archived sibling stores outside git, and the analysis script is uncommitted — unreproducible post-hoc. My five runs corroborate the calm-phase silence and the zero-`:info` reference behavior. Committing the window-analysis tooling (worker already suggests checker-izing it) would make this criterion re-runnable. |

## Suggestions (non-blocking)

- Second the worker's image follow-ups (`git`, `sudo`, `gnuplot-nox` in
  `env/Dockerfile`), and add Finding 1's multi-cert CA import fix and
  Finding 2's stale usage line to the same future env job.
- Second ending runs at workload exhaustion; pairing it with a longer
  op phase (rate or budget, once elle lifts the knossos cliff) directly
  addresses Finding 5.
- Commit the `:info`-vs-window analyzer (and promote to a checker in
  M1, as the worker proposes) so criterion-3 evidence is reproducible.
- `run.sh test` could `trap` INT/TERM and kill the in-container harness
  (or run `docker compose exec` with a TTY) to make Ctrl-C mean stop.
