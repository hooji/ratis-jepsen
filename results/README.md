# results/ — published reference runs

This directory is the evidence. It holds selected output from real
harness runs — checker verdicts, run logs, histories, and evidence
excerpts — committed so that a visitor can see what this harness
actually found without installing Docker or running anything. It is the
one deliberate exception to this repository's no-run-artifacts rule
(`docs/PROCESS.md`), created by Job 14 and governed by its brief.

## What is here

| Directory | Version under test | Runs |
|---|---|---|
| [`2026-08-07-ratis-3.2.2/`](2026-08-07-ratis-3.2.2/) | Apache Ratis **3.2.2** (Maven Central) | 20 runs: the full scenario sweep, plus the runs that are *supposed* to fail |
| [`2026-08-07-ratis-3.3.0-rc2/`](2026-08-07-ratis-3.3.0-rc2/) | Apache Ratis **3.3.0 RC2** (release candidate — **not a released version**) | 21 runs: the same sweep, plus the mixed-version / rolling-upgrade runs spanning 3.2.2 → 3.3.0 RC2 (plus two runs **voided by a tooling collision**, published under `VOIDED-collided-runs/` — see that README) |

Each version directory has a README that stands alone: what was
tested, a results table with one row per run, what each run proved,
what its evidence assertions counted, and what these runs do *not*
cover. Start there.

## How to read a run directory

Every `<scenario>/` directory contains, at minimum:

- `results.edn` — the composed checker verdict, exactly as the harness
  wrote it: `:valid?`, per-key linearizability / counter results,
  liveness, and the evidence checkers (install-snapshot counts,
  committed configuration transitions, joiner installs, lazyfs fault
  acknowledgements, client retries, applied rolls).
- `jepsen.log.gz` — the harness's full run log.
- `history.txt.gz` / `history.edn.gz` — the complete operation history,
  the raw material the checkers analyzed.
- where a run's story lives in server logs: `node-log-excerpts.txt`
  (grep'd evidence lines, with node/file/line provenance) and, for the
  runs that warrant it, full per-node logs (`*.log.gz`).
- for failed (convicting) runs: knossos's `linear-key<k>.svg` conviction
  diagrams.

## Expected-red runs — read this before reading any table

Two kinds of runs in these directories **fail on purpose**, and are
named `EXPECTED-RED-…` at the directory level so they cannot be
mistaken for harness failures:

- **`EXPECTED-RED-seeded-stale-reads/`** — the *test of the test*. The
  SUT is started with a deliberately planted bug (`--seed-bug
  stale-reads`: linearizable reads answered from a ~500 ms-lagging
  copy) and the harness **must** convict it. A green run here would
  mean the harness had gone blind. Exit code 1 with `:valid? false` on
  all five keys is the **pass** condition for this run.
- **`EXPECTED-RED-q14-retry-cache-expiry/`** (3.2.2 directory) — the
  documented retry-cache expiry boundary, re-armed on purpose: the
  server's retry cache window is shrunk to 500 ms while the client's
  retry delay is 5 s, violating the flag's own contract, so same-callId
  retries arrive after the cache entry expired and are applied twice.
  The counter checker convicting with `:double-count` violations is the
  **expected** outcome and the demonstration that the checker can see
  double-applies at all.

Everything else is expected green, and was green on these dates.

## Provenance

Every run here was performed on 2026-08-07 at harness commit `4126b48`.
Most runs executed in public GitHub Actions (one fresh `ubuntu-latest`
runner per scenario; the per-version READMEs link every CI job); the
runs CI cannot dispatch (flags the workflow does not expose) ran
locally on a 4-core Linux x86_64 dev container, and are marked as such.
The run ledger `docs/RUNS.md` records the same runs in summary form.
