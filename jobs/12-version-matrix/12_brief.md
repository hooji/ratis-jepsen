# Job 12 — M5: version matrix and mixed-version topology

*Coordinator brief, 2026-08-06.*

**Read `jobs/README.md` first — binding.** Then `docs/PLAN.md` (M5,
Q15), `docs/DESIGN.md` §1.1, `docs/BACKLOG.md` items 7–9, and this
brief. Base on current `main` (M4 complete: durability faults merged).

## Context

Everything so far runs against Ratis **3.2.2**, pinned since Job 01.
The 3.2.2 line was the evaluation's target; **3.3.0 has since been
released** (it was at RC2 during the evaluation, 2026-07-30). The
harness has always carried `ratis.version` as a Maven property
precisely so this day would be a parameter change rather than a
rewrite. This job proves that, runs the suite against both versions,
and adds the mixed-version topology the studied precedent had.

Three of our banked upstream candidates (BACKLOG 7–9) are *3.2.2*
observations. Whether they persist at 3.3.0 changes what we would
report and how — so this job also re-runs their specific probes
against the newer line. Determining that by running is the point;
release notes are a hint, not evidence.

## Deliverables

1. **Version parameterization proven end to end**: `--ratis-version`
   (or equivalent) selects the SUT build; the harness's own
   `ratis-client`/`ratis-grpc` dependency must match the server under
   test (state how you resolve this — an alias, a property, or a
   documented constraint if the client must be pinned separately).
   Both 3.2.2 and 3.3.0 build, deploy, and run without source
   changes to the SUT beyond the version input; if 3.3.0 requires a
   source change, that is a finding — report it precisely (API drift
   between minor releases is exactly what a matrix exists to catch).
2. **Full-suite runs at 3.3.0**: register under `partition`, `crash`,
   `mixed-all`; counter under `crash`; one durability scenario;
   snapshot-churn and membership with their evidence assertions. Any
   scenario that behaves differently at 3.3.0 than at 3.2.2 is a
   headline result — green or red.
3. **Candidate re-probes at 3.3.0** (BACKLOG 7–9), each stated as
   persists / fixed / changed-shape, with evidence:
   - the base-class lifecycle trap and the install-retry behavior
     (BACKLOG 7) — note the SUT now carries its own lifecycle
     handling, so probe the *library* behavior deliberately rather
     than relying on the fixed SUT to mask it;
   - `GroupInfoReply` conf field on the wire (BACKLOG 8) — a
     one-call check;
   - the staged-listener startup state (BACKLOG 9) — re-run Job 08's
     probe sequence.
4. **Mixed-version topology** (`--mixed-version <old>,<new>` or
   similar): a group where some voters run one version and the rest
   another, exercising the wire compatibility both directions.
   Minimum: a register run under `partition` and one under `crash`,
   plus a rolling-upgrade style sequence (restart nodes one at a time
   onto the newer version while the workload runs). Expectations
   stated in advance in your report.
5. **CI**: the workflow gains a version input (default 3.2.2) and,
   if cheap, a mixed-version dispatchable token. Itemize the diff.
6. **Runs + ledger** (`docs/RUNS.md` append): every run above, with
   version(s) recorded in each entry. Add a short comparison table:
   scenario × version × outcome.
7. **`jobs/12-version-matrix/12_report.md`** per `jobs/README.md`.

## File ownership

`sut/**` (version plumbing only — no behavioral change),
`harness/**`, `env/**` (only if a second Ratis version needs staging
support), `docs/RUNS.md` (append), `.github/workflows/jepsen.yml`
(itemized), `jobs/12-version-matrix/12_report.md`.
**Parallel-safe with: none.**

## Acceptance criteria (command + output excerpt each)

1. Suites green; a 3.2.2 run reproduces its ledger entry (no
   regression from the parameterization).
2. Every 3.3.0 run listed in deliverable 2, with outcomes.
3. All three candidate re-probes answered with evidence.
4. Mixed-version runs including the rolling sequence, expectations
   stated in advance and judged.
5. Comparison table present in the ledger.
6. Established reporting: analysis times, `:info` sanity, ownership,
   headers, report.

## Non-goals

Upstream filing (owner-gated), new nemeses, new workloads, elle
migration, probe-script hardening (BACKLOG 11 — a separate small job),
performance comparison between versions.

## Note

A version bump that silently changes behavior is the single most
valuable thing a matrix can catch, and it cuts both ways: if 3.3.0
quietly fixed one of our candidates, our upstream framing changes from
"here is a defect" to "here is a defect you already fixed — here is
the test that proves it stays fixed." Either outcome is worth having;
state which one you found, precisely.
