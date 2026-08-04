# Job 04 — integration: register workload, partition nemesis, M0 exit gate

*Coordinator brief, 2026-08-04.*

**Before anything else, read `jobs/README.md` — it is binding.** Then
`docs/PLAN.md`, `docs/DESIGN.md` (§0 exit criteria, §2.5 workload/
checking budget, §2.6 deployment contract, §5 validation procedure),
and this brief. Base your branch on **current** `main` — Jobs 01–03 are
all merged there.

## Context

Everything exists except the test itself. Job 01: the SUT
(`sut/ratis-kv`, wire protocol PUT/CAS/GET, `--seed-bug stale-reads`).
Job 02: the Docker topology (`env/run.sh up|down`, control + `n1..n7`,
ssh, `validate.sh`) — note its `validate.sh` just had a revision merged
(current-leadership semantics); a round-2 re-review is in flight, so if
you see a small follow-up land on `validate.sh`, pull and continue —
your job doesn't touch that file. Job 03: the harness core
(`harness/` — client with unit-tested outcome map, `db.clj` written to
the §2.6 contract but never yet run against real containers,
env-contract ns, CLI skeleton). **This job wires them together, makes
`env/run.sh test` real, and closes M0 by producing the two reference
runs: a green one, and a red one where the harness catches the SUT's
seeded bug.**

Expect integration friction: `db.clj` meets real containers here for
the first time. Fixing what that surfaces in `harness/**` is in scope;
document each fix in your report.

## Deliverables

1. **`harness/src/ratis_jepsen/workload/register.clj`** — the register
   workload per DESIGN §2.5: ops `:r` / `:w v` / `:cas [old new]`
   roughly equally mixed over **`jepsen.independent` keys — 5 keys,
   ≤ 400 ops per key (hard cap in the generator), concurrency 10,
   ~10 ops/s per worker**; checker = `jepsen.checker/linearizable`
   with the `knossos.model/cas-register` model, wrapped per-key via
   `independent/checker`, composed with `checker/timeline`,
   `checker/perf`, `checker/unhandled-exceptions`, and `checker/stats`.
2. **`ratis-jepsen.nemesis`** — thin M0 wiring:
   `nemesis/partition-random-halves` with a 15 s on / 15 s off cycle
   generator; `--nemesis none|partition` CLI choice. (M2 grows this
   namespace; keep it a clean seam.)
3. **`ratis-jepsen.core`** — real CLI: `--workload register`,
   `--nemesis`, `--time-limit` (default 300), `--key-count` (5),
   `--ops-per-key` (400), `--concurrency` (10), and **`--seed-bug
   stale-reads`** which plumbs through `db.clj` to append the flag to
   the SUT start command on every node (extending `db.clj` is in your
   ownership).
4. **`env/run.sh test` made real** (replace the Job 02 stub *only* —
   that subcommand's body is the single `env/` change you may make):
   ensures the SUT tarball exists (build via
   `/ratis-jepsen/sut/ratis-kv/mvnw -f /ratis-jepsen/sut/ratis-kv/pom.xml
   -q package` inside control if absent), runs the harness on control
   against nodes `n1..n5`, passes through the CLI args above, bind-lets
   Jepsen's `store/` land under `/ratis-jepsen/store/` (gitignored),
   and **propagates the harness exit code** (0 = checker valid,
   non-zero = violation or error).
5. **The M0 exit-gate runs** (DESIGN §5), executed by you:
   - **Reference GREEN**: `env/run.sh up && env/run.sh test
     --nemesis partition --time-limit 300` → checker `:valid? true`,
     exit 0.
   - **Reference RED**: same plus `--seed-bug stale-reads` → checker
     `:valid? false`, non-zero exit, and the analysis names a concrete
     non-linearizable operation.
   - **Outcome-mapping sanity**: from the green run's history, show
     that `:info` results occur only during/adjacent to nemesis
     windows (a flood of `:info` in calm phases means timeouts/mapping
     are wrong — fix, don't hand-wave).
6. **`docs/RUNS.md`** (new file, granted to this job): the run ledger —
   one dated entry per reference run: command, versions (ratis 3.2.2,
   jepsen version), duration, verdict, one-paragraph summary, and for
   the red run the violating-op excerpt. Summaries only — `store/`
   never enters git.
7. **`jobs/04-integration/04_report.md`** per `jobs/README.md`.

## File ownership

May create/modify: `harness/**`, `env/run.sh` (the `test` subcommand
body only), `docs/RUNS.md`, `jobs/04-integration/04_report.md`.
Nothing else — not `validate.sh`, not `sut/**`, not the Dockerfile/
compose. **Parallel-safe with: none.**

## Acceptance criteria (each with command + output excerpt in your report)

1. From a clean checkout: `env/run.sh up` then the reference **green**
   run — exit 0, checker `:valid? true`; include the checker summary
   and the run's wall-clock.
2. The reference **red** run — non-zero exit, `:valid? false`, with the
   concrete non-linearizable op quoted from the analysis.
3. Outcome-mapping sanity evidence: counts of `:info` ops inside vs
   outside nemesis windows for the green run (state how you computed
   the windows).
4. Knossos stays bounded: per-key op caps enforced in the generator
   (show the cap code) and total checker analysis time reported (if
   analysis exceeds ~5 minutes, shrink ops-per-key and say so).
5. A `--nemesis none` short run (e.g. 60 s) is green — the harness
   doesn't manufacture failures on a calm cluster.
6. `env/run.sh down` afterward leaves `docker ps` clean; `store/` and
   `target/` absent from the PR diff.
7. Apache-2.0 headers; ownership respected (diff shows `env/run.sh`
   changed only in the `test` body); report present per
   `jobs/README.md`.

## Non-goals

elle (M1), crash/pause nemeses (M1), membership/snapshot churn (M2),
follower-targeted reads (M2), the `ADD` increment workload (M3), CI
workflows (M1, after go-public), lazyfs (M4), any `sut/**` change —
if the SUT itself needs a change to pass the gate, **stop and report**
(that's a coordinator problem, not yours).

## Notes

- If the green run reports a *real* linearizability violation (no seed
  bug), do not tune it away and do not suppress it: capture the store,
  reduce if you can, and report — that outcome outranks completing the
  gate. (DESIGN's premise is that this run is green; a genuine red here
  is a discovery.)
- Jepsen's control-node execution: the harness runs *on control* via
  `run.sh test` (docker exec/ssh into control) — keep the harness
  itself ignorant of Docker; it sees nodes per `env-contract` and
  `jepsen.control` ssh, exactly as `db.clj` already assumes.
- `db.clj`'s first contact with real containers is the likeliest
  friction point (paths, ssh env, tarball glob). Fixes there are
  expected and in-ownership; each one goes in the report.
