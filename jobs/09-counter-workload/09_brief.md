# Job 09 — M3: the exactly-once increment workload

*Coordinator brief, 2026-08-06.*

**Read `jobs/README.md` first — binding.** Then `docs/PLAN.md` (M3,
Q14), `docs/DESIGN.md` §2.4/§2.6, and this brief. Base on current
`main` (M2 complete: membership + snapshot churn, fixed SUT lifecycle).

## Context

The evaluation chose Ratis for exactly this: server-side retry
deduplication (`(ClientId, callId)` cache, rebuilt at apply on every
replica) making non-idempotent operations safe to retry across
failover — and upstream's own RATIS-2542 wishlist names "repliedIndex
linearizability" as a thing they want tested. This job tests it for
real: increments that would double-count under at-least-once, driven
through leader kills. Plus the Q14 flip side: make the *documented*
boundary (retry-cache expiry) observable in a red-by-design run — the
calibration evidence for the StateStore L3 provider's
Indeterminate-retry rule.

## Deliverables

1. **SUT `ADD`** (minimal `sut/**` diff): wire op `ADD <k> <delta>` →
   `VAL <new>` (absent key = 0), codec + state machine + tests in the
   established style.
2. **SUT flag `--retry-cache-expiry-ms <ms>`** wiring
   `raft.server.retrycache.expirytime` (flag absent ⇒ Ratis default
   60 s untouched). For the Q14 run only.
3. **`--workload counter`**: per-key known-delta `:add` ops mixed with
   `:read`s over independent keys; client = the standard bounded
   same-callId retry config, plus a `--retry-delay-ms` knob injecting
   client-side delay between attempts (used to overshoot a shrunken
   expiry window). Checker: counter semantics — every final/observed
   value must satisfy exactly-once for `:ok` adds (sum preserved) and
   0-or-1 inclusion for `:info` adds (jepsen's counter checker or an
   explicit per-key bounds checker; document the choice). Any `:ok`
   double-count or loss = conviction.
4. **Evidence law**: a dedicated dedup run must prove retries actually
   happened (client-side retry-attempt count > 0, reported per run;
   zero-retry runs fail with a distinct error — a dedup test that
   never retried tested nothing).
5. **Runs + ledger** (`docs/RUNS.md` append):
   - counter under `crash` (leader-biased) ×2 green — the
     retry-cache-across-failover proof, with retry counts quoted;
   - counter under `mixed-all` green;
   - **Q14 red-by-design**: expiry shrunk (e.g. 2000 ms) +
     `--retry-delay-ms` overshooting it ⇒ a double-apply observed and
     convicted by the counter checker; ledger entry explicitly framed
     as the L3 Indeterminate-rule calibration (exact configs quoted);
   - seeded-red (stale-reads) sanity on the register workload
     unchanged (no regression).
6. **CI**: extend the workflow minimally so a counter scenario can be
   dispatched (e.g. scenario token `counter-crash`); itemize the diff.
7. **`jobs/09-counter-workload/09_report.md`** per `jobs/README.md`.

## File ownership

`sut/**` (minimal: ADD + expiry flag), `harness/**`, `docs/RUNS.md`
(append), `.github/workflows/jepsen.yml` (minimal scenario-token
addition, itemized), `jobs/09-counter-workload/09_report.md`.
**Parallel-safe with: none.**

## Acceptance criteria (command + output excerpt each)

1. SUT + harness suites green (ADD codec/SM tests; checker units incl.
   a fabricated double-count conviction and a 0-or-1 `:info` case).
2. Crash ×2 green with nonzero retry counts quoted; mixed-all green.
3. Q14 run: conviction output + configs quoted; store preserved.
4. Zero-retry evidence law demonstrated (fixture or defanged run).
5. Established reporting: analysis times, `:info` sanity, ownership
   (SUT + workflow diffs itemized), headers, report.

## Non-goals

lazyfs (M4), version matrix (M5), register-workload changes beyond the
no-regression check, membership/listener work, upstream filing (the
backlog holds three candidates; engagement is a later, owner-gated
step).

## Note

If exactly-once *fails* under the default 60 s window — an `:ok` add
lost or doubled with retries inside the window — that contradicts both
the evaluation's central claim and Review 03/05's soundness analysis:
preserve everything, triage with extra care (client identity/callId
reuse in your workload client is the first suspect), and report
loudly. That would be the most consequential finding this project
could produce.
