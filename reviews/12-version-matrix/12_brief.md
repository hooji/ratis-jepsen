# Review 12 — version matrix and mixed-version topology (worker PR #25)

*Coordinator brief, 2026-08-06.* **Read `reviews/README.md` first.**
Standard: `jobs/12-version-matrix/12_brief.md` + documented
deviations. Requires Docker.

**Coordinator correction up front:** the job brief asserted that 3.3.0
"has since been released." That was my error — unverified, and wrong.
The worker caught it and ran against the RC2 staging artifacts
instead, labelling them as such. Treat the brief's premise as void and
the worker's handling as the thing under review: **every claim in this
PR that says "3.3.0" must be traceable to a specific artifact whose
provenance is stated.** This matters beyond bookkeeping — these
results are candidates for external quotation, and "tested against the
released 3.3.0" would be false.

## Emphasis

1. **Artifact provenance.** Verify the byte-verification the worker
   describes: which artifacts, from where, matched against what.
   Confirm the version label used in code, ledger, workflow inputs and
   report is unambiguous about being a release candidate (not bare
   "3.3.0"). If any surface still reads as a released version, that is
   a blocking documentation defect — cheap to fix, expensive to ship.
2. **The in-JVM library probe's validity — the load-bearing claim.**
   BACKLOG 7's re-probe uses a deliberately naive `BaseStateMachine`
   subclass in a new `harness/probe/` source root, because the shipped
   SUT's own lifecycle handling would mask the trap. Its entire
   evidentiary weight rests on the control: reproducing the Job 08
   conviction at 3.2.2 before rendering any verdict at RC2. Re-run
   both arms yourself. Then judge representativeness: is the naive
   subclass what an ordinary integrator would plausibly write (i.e.
   does the probe test the *library's* trap), or has it been shaped
   until it fails? Say which, plainly.
3. **BACKLOG 8 "fixed" — verify, and follow the consequences.**
   Confirm the conf field genuinely arrives on the wire at RC2 (not
   merely a differently-shaped absence), and then check what that
   means for us: the harness's log-census workaround was built because
   the field was missing. Does it still function at RC2? Is it still
   needed there? Is there now a version-dependent branch, and is it
   handled honestly? Also: BACKLOG 8's entry will need rewriting from
   "candidate defect" to "fixed upstream; our test proves it stays
   fixed" — confirm the report gives me the facts to do that
   accurately.
4. **BACKLOG 7 and 9 "persist" — verify each** with the same rigour
   the original findings received (Job 08 / Review 08 are the bar).
   For 9, re-run the listener sequence at RC2 and confirm the
   mechanism is unchanged, not merely the symptom.
5. **Mixed-version runs**: expectations must have been committed in
   the report *before* the outcomes are presented; confirm the rolling
   upgrade genuinely restarts nodes onto the other version under load
   (evidence from node state, not narration), and that both wire
   directions were exercised.
6. **The classpath skew guard**: confirm it actually refuses a skewed
   client/server pairing (test it deliberately) rather than merely
   documenting the requirement — a silent skew would invalidate
   results without failing.
7. **No-regression**: a 3.2.2 run reproduces its ledger entry; suites
   green; ownership (`sut/**` limited to version plumbing, no
   behavioural change); workflow diff itemized.

## Probe (≥1)

Run a mixed-version group in the *reverse* pairing from the report's
rolling upgrade (new→old, if the report only exercised old→new); or
attempt a run with a nonexistent version string and confirm the
failure is immediate and comprehensible rather than a late, confusing
error.

Deliver `reviews/12-version-matrix/12_report.md`; verdict PR
`Review 12: <verdict>`; self-merge if report-only.
