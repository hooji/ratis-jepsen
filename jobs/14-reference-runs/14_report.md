# Job 14 report — published reference runs (results committed to the repository)

## Summary

`results/` now exists: 43 real runs performed on 2026-08-07 at harness
commit `4126b48`, 41 published as reference rows — the full
CI-dispatchable scenario surface against both ratis 3.2.2 and the
3.3.0 RC2 staging artifacts (37 CI runs across two public GitHub
Actions sweeps, every job green on its only attempt), plus six local
runs for the flags CI cannot pass (`--reads mixed` at both versions,
the Q14 expected-red, a new Q14 *control* run that brackets the
retry-cache boundary inside one published set — and one collided pair,
voided and published as an incident). Two
decisions deserve the hardest look: (1) **an orchestration mistake of
mine ran the RC2 follower-reads scenario twice concurrently on one
cluster** — I voided both runs on the collision facts, published both
voided outcomes under `VOIDED-collided-runs/` with analysis, and ran
one clean replacement; (2) I installed gnuplot into the *local* control
container (runtime only, no repo change) so the local stores carry
latency/rate charts, embedded in the version READMEs alongside the
knossos conviction SVGs — the 37 CI stores have no charts because the
env image ships no gnuplot.

## What was built

| Path | What |
|---|---|
| `results/README.md` | index: what the directories are, how to read a run directory, the EXPECTED-RED warning, provenance |
| `results/2026-08-07-ratis-3.2.2/README.md` | standalone version report: provenance, green table (17 rows), expected-red table (2 rows), per-family evidence prose with verbatim quotes, embedded conviction SVG + Q14 latency chart, limits, anomalies, kept/dropped list |
| `results/2026-08-07-ratis-3.2.2/<scenario>/…` | 19 run directories: `results.edn`, `jepsen.log.gz`, `history.{txt,edn}.gz` always; `node-log-excerpts.txt` where evidence lives in server logs; full node logs (gz) + knossos `linear-key*.svg` for expected-red and torn-write; latency/rate PNGs on local runs |
| `results/2026-08-07-ratis-3.3.0-rc2/README.md` | same, for RC2 (17 green + 1 expected-red + 3 mixed-version), RC-labelled on every surface |
| `results/2026-08-07-ratis-3.3.0-rc2/<scenario>/…`, `mixed-version-3.2.2-and-rc2/…` | 20 run directories (mixed-version runs grouped and marked as spanning both versions) |
| `results/2026-08-07-ratis-3.3.0-rc2/VOIDED-collided-runs/` | the two voided concurrent runs: `results.edn` + `jepsen.log.gz` each, and a README with the incident timeline and why both verdicts are uninterpretable |
| `README.md` | one added paragraph after the intro: 👉 link to `results/`, framing, the EXPECTED-RED warning (nothing else touched) |
| `docs/RUNS.md` | one appended ledger entry summarizing the batch, pointing into `results/` |
| `jobs/14-reference-runs/14_report.md` | this report |

No source, workflow, SUT, or `.gitignore` changes. (`.gitignore` needed
no negation: committed logs are `.log.gz`/`.txt`, which the global
`*.log` rule does not match; verified with `git check-ignore`.)

## The full run list

**CI sweep A — ratis 3.2.2** — workflow run
[31205755119](https://github.com/hooji/ratis-jepsen/actions/runs/31205755119)
(dispatched on `claude/reference-runs-brief-0dlb9n` @ `4126b48`, inputs:
default time-limit 300, `ratis-version=3.2.2`, 16 scenario tokens).
17 jobs, 17 successes, first attempt:

| Scenario | Verdict | ok/fail/info | Wall |
|---|---|---|---|
| none | green | 1093/407/0 | 42 s |
| partition | green | 1096/404/0 | 309 s |
| crash | green | 1108/392/0 | 311 s |
| pause | green | 1125/375/0 | 310 s |
| mixed | green | 1076/414/10 | 310 s |
| mixed-all | green | 1090/410/0 | 322 s |
| transfer | green | 1086/414/0 | 312 s |
| snapshot-churn | green; 2 install events | 1076/424/0 | 308 s |
| membership | green; 21 conf transitions | 1078/422/0 | 319 s |
| membership-snapshot-churn | green; 8 transitions, 3/3 joiners installed | 1497/594/0 | 312 s |
| listener-probe | green; BACKLOG-9 wedge recorded | 1118/382/0 | 72 s |
| counter-crash | green; exactly-once, 217 retries | 2045/0/31 | 309 s |
| unsync-drop | green; 15 lazyfs acks | 1123/377/0 | 313 s |
| unsync-drop-all | green; 20 acks | 1009/433/101 | 314 s |
| counter-unsync-drop | green; 14 acks, 141 retries | 2075/2/19 | 313 s |
| torn-write | green; tear fired, victim refused loudly | 1068/432/0 | 54 s |
| red-gate (seeded stale-reads) | **RED as intended** (exit 1, all 5 keys) | 1096/389/15 | 129 s |

**CI sweep B — ratis 3.3.0 RC2** — workflow run
[31205774470](https://github.com/hooji/ratis-jepsen/actions/runs/31205774470)
(same ref/commit; inputs: `ratis-version=3.3.0`,
`mixed-version=3.2.2,3.3.0`,
`ratis-repo-url=https://repository.apache.org/content/repositories/orgapacheratis-1182/`).
20 jobs, 20 successes, first attempt:

| Scenario | Verdict | ok/fail/info | Wall |
|---|---|---|---|
| none | green | 1088/412/0 | 39 s |
| partition | green | 1121/377/2 | 309 s |
| crash | green | 1082/410/8 | 311 s |
| pause | green | 1101/399/0 | 309 s |
| mixed | green | 1101/397/2 | 310 s |
| mixed-all | green | 1080/420/0 | 323 s |
| transfer | green | 1061/439/0 | 312 s |
| snapshot-churn | green; 2 install events | 1113/387/0 | 313 s |
| membership | green; 21 transitions | 1067/433/0 | 319 s |
| membership-snapshot-churn | green; 8 transitions, 2/2 post-snapshot joiners installed | 1531/531/5 | 316 s |
| listener-probe | green; wedge persists at RC2 | 1074/426/0 | 73 s |
| counter-crash | green; 243 retries | 2061/3/35 | 311 s |
| unsync-drop | green; 16 acks | 1106/394/0 | 313 s |
| unsync-drop-all | green; 20 acks | 987/407/105 | 318 s |
| counter-unsync-drop | green; 16 acks, 155 retries | 2047/3/25 | 313 s |
| torn-write | green; refused loudly (victim n3) | 1078/422/0 | 53 s |
| mv-partition (n1–n3 old / n4–n5 new) | green | 1150/350/0 | 311 s |
| mv-crash (same split) | green | 1117/372/11 | 313 s |
| rolling-upgrade (5/5 rolls) | green | 1063/437/0 | 173 s |
| red-gate (seeded stale-reads) | **RED as intended** (exit 1, all 5 keys) | 1104/395/1 | 129 s |

**Local runs** (this dev container; 4-core x86_64; same Docker
topology; used only for flags the workflow does not expose):

| Run | Command core | Verdict | Numbers |
|---|---|---|---|
| follower-reads @3.2.2 | `partition --reads mixed` | green, exit 0 | 1021/479/0; 156/414 ok reads follower-served; 318 s |
| Q14 expected-red @3.2.2 | `counter quorum-pause --retry-cache-expiry-ms 500 --retry-delay-ms 5000 --rate 3 --ops-per-key 1200` | **RED as intended**, exit 1: `:double-count` all 5 keys, zero `:info` | 1906/0/0; 305 retries; 320 s |
| Q14 control @3.2.2 (new) | same minus the expiry flag (default 60 s window) | green, exit 0, zero violations | 1893/0/0; 308 retries; 313 s |
| follower-reads @RC2 — **collided pair, VOIDED** | `partition --reads mixed --ratis-version 3.3.0` ×2 concurrently (see Deviations) | run A exit 0 (uninterpretable), run B exit 2 (`:unknown` ×5 keys) | published under `VOIDED-collided-runs/` |
| follower-reads @RC2 — clean re-run | same, alone on a quiesced topology | green, exit 0 | 1055/445/0; 183/426 ok reads follower-served (`n5:77 n4:50 n3:29 n1:27`); 317 s, 0.9 s analysis |

## How it was verified

Per acceptance criterion:

1. **Both version directories exist, correctly named and dated.**
   `ls results/` → `2026-08-07-ratis-3.2.2  2026-08-07-ratis-3.3.0-rc2
   README.md`. RC labelling audited mechanically:
   `grep -rniE '\b3\.3\.0\b' results/ --include='*.md' | grep -viE
   'rc2|rc-2|candidate|staging|3\.3\.0-rc|dev/ratis|if the vote|promoted'`
   → **zero lines**, i.e. no sentence in any published Markdown reads
   as a released 3.3.0 (also re-read both READMEs end-to-end).
2. **Per-version READMEs standalone-complete**: each carries
   what-was-tested (incl. RC provenance from Job 12's verification),
   a one-row-per-run table linking artifacts + CI job, evidence
   assertions quoted verbatim (install pair, conf-change line, lazyfs
   acks, torn checksum refusal, retry totals, roll map), limits, and
   anomalies. Verified by re-reading each with the brief's checklist
   beside it.
3. **`results/README.md` index** exists (what/how-to-read/red-warning/
   provenance).
4. **Expected-red labelled at every level**: directory names
   (`EXPECTED-RED-seeded-stale-reads`, `EXPECTED-RED-q14-…`), separate
   tables under a "these FAIL on purpose" heading, and prose in index +
   both version READMEs + the main-README framing sentence. A skim of
   any green table meets no red row.
5. **Evidence quoted, not summarized away**: e.g. 3.2.2 README quotes
   `followerNextIndex = 910 but logStartIndex = 1056, send snapshot …` /
   `receive installSnapshot: n5->n3#0-t2`, the committed
   `old=peers:[…]` conf line, `will persist 14 bytes from offset
   54443` + `Calculated checksum is 70ACB010 but read checksum is
   00000000`, and the Q14 per-key `:double-count` bounds; RC2 README
   equivalently (its own runs' lines, not 3.2.2's).
6. **Size totals**: `du -sh` → 3.2.2 directory **3.7 MB**, RC2
   directory **3.0 MB** (incl. the voided pair's 0.24 MB), whole
   `results/` tree **6.7 MB across 246 files** — far under the 50 MB
   per-directory budget; exclusions stated in each README's
   "committed/left out" section. (Uncompressed store totals: sweep A
   ≈45 MB, sweep B ≈49 MB, local ≈16 MB.)
7. **Main README links results within the first screenful** — the 👉
   paragraph sits directly under the opening description, before
   `## Status`.
8. **RUNS.md appended** (one batch entry, established style, pointing
   into `results/`); **`.gitignore` untouched** — `git diff --stat
   .gitignore` empty, and `git check-ignore` confirms committed
   `.log.gz`/`.txt` files pass while `store/` stays ignored.
9. **This report** carries the full run list with verdicts (above),
   the CI/local split with URLs, what was repeated and why (one
   scenario, because of the collision — both voided outcomes
   published), and what was deliberately not run (below).

Spot verification of committed artifact integrity: for each of six
randomly picked run dirs, `zcat jepsen.log.gz | tail` shows the
verdict banner matching the README row ("Everything looks good!" /
"Analysis invalid!"), and `results.edn`'s `:valid?` matches the table.

## Deviations from the brief

1. **One scenario was run more than once — with both outcomes
   published.** The RC2 `partition --reads mixed` run was accidentally
   started twice (a background wrapper I wrongly believed dead fired
   its queued copy; I manually started the second). Two harnesses drove
   the same cluster concurrently for ~3.5 min: run B's standard pre-run
   teardown wiped run A's storage mid-flight, and both wrote the same
   wire keys. I voided both **on the collision facts** — the decision
   predates and is independent of their verdicts (A: exit 0; B: exit 2
   `:unknown`) — published both under `VOIDED-collided-runs/` with a
   timeline README, and ran one clean replacement alone. This is the
   brief's "run it again and publish both outcomes" rule applied to an
   infrastructure fault of my own making; no verdict-shopping occurred
   (both discarded runs were non-red).
2. **gnuplot installed into the local control container at runtime**
   (`apt-get install gnuplot-nox` inside the running container; no
   repo file changed) so jepsen's composed perf checker would draw
   latency/rate charts for the local runs — the owner asked mid-job
   for run-generated charts embedded in the READMEs. Consequence: the
   five local stores carry PNGs, the 35 CI stores do not; called out
   in both READMEs' anomaly sections, with an env suggestion below.
3. **The brief's directory sketch shows `<scenario>/` only**; I added
   the `EXPECTED-RED-` prefix to the two red directories, a
   `mixed-version-3.2.2-and-rc2/` grouping, and the
   `VOIDED-collided-runs/` directory — all in service of the brief's
   own integrity rules (unmistakable labelling; mixed runs marked as
   spanning both; no silent discards).
4. **Run counts in the index README** say "20 runs / 19 runs" counting
   reference rows (the voided pair counts as an incident, not
   reference rows); the exact accounting is this report's run list.

## Known gaps and risks

- **The RC2 label's shelf life**: if the 3.3.0 vote passes, readers
  should verify the promoted Central jars against the staging sha512s
  rather than assume; if it fails, the RC2 directory documents bits
  that never shipped (its README says so).
- **CI artifact links expire**: the workflow's uploaded stores vanish
  after 7 days; the run/job *pages* and logs persist. The committed
  selections are the durable evidence — that is the point of this job.
- **One run per scenario on this date**: repetition statistics live in
  `docs/RUNS.md`'s earlier entries; these directories make no
  flakiness claims.
- **Run A's green under interference** (VOIDED dir) is a standing
  reminder that a green is only meaningful under the run's fault
  model; the VOID README says this explicitly so nobody re-adopts the
  run later.
- **`store/` in the worker environment** held the full local stores
  (including the voided pair's complete node logs); only selections
  were committed. If the coordinator wants more of the voided
  evidence preserved, say so before this environment is reclaimed.

## Suggestions (out of scope)

- **Add gnuplot to the env image** (one apt token in the Dockerfile):
  every CI store would then carry the latency/rate charts readers
  respond to; cost is a few MB of image.
- **A `--run-tag` harness flag** (or similar) that suffixes the wire
  keyspace and store name would make concurrent-harness collisions
  both impossible (disjoint keys) and visible (distinct stores);
  today nothing stops two `run.sh test` invocations from sharing a
  cluster.
- **CI could expose `--reads` and the Q14 levers** as optional
  workflow inputs, moving the last local-only runs onto neutral
  infrastructure.
- The `jepsen` workflow badge on the main README now reflects sweeps
  run from job branches too; if the owner wants the badge pinned to
  main-branch dispatches only, that is a one-line workflow change
  (out of my ownership).
