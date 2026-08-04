# Review 03 — harness core (worker PR: `Job 03: harness core: Clojure skeleton, client + outcome map, db lifecycle`)

*Coordinator brief, 2026-08-04.*

**Read `reviews/README.md` first — it is binding.** The standard is
`jobs/03-harness-core/03_brief.md` plus `docs/DESIGN.md` §2 (the outcome
table §2.4, deployment contract §2.6). This file adds emphasis.

## Baseline (always)

Reproduce in the worktree: `sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml
-q install`, then from `harness/`: `clojure -M:test` (all green; note
the resolved jepsen version) and `clojure -M:run test --help`. Verify
ownership (`harness/**` + report only), headers, no artifacts, report
accuracy.

## Emphasis

1. **`RaftRetryFailureException` classification — treat as potentially
   BLOCKING until proven sound.** The worker discovered that under
   `RetryPolicies.noRetry()`, `NotLeaderException`/`LeaderNotReadyException`
   reach the caller wrapped in `RaftRetryFailureException` (cause
   nulled) and classify it `:fail` (definite-not-applied). The danger:
   if that same wrapper can carry an **ambiguous** failure — a
   `TimeoutIOException` or transport `IOException` after the request may
   have reached the leader — then `:fail` is unsound, and an
   actually-applied write recorded as `:fail` will manufacture **false
   linearizability violations** in every future run. Your job: settle
   this from the `ratis-3.2.2` client sources (fetch `ratis-client`'s
   `BlockingImpl.sendRequestWithRetry` / `RaftClientImpl` exception
   handling): enumerate exactly which underlying failures surface as
   `RaftRetryFailureException` under a no-retry policy, and which
   surface directly (thrown as-is). Then confirm empirically: construct
   the scenarios you can (the integration test's servers-down write is
   one; a mid-call timeout is another if you can arrange it) and check
   the classifier's verdicts. If any possibly-applied failure mode maps
   to `:fail`, that is **blocking**; the sound direction is `:info`.
   Quote the client-source lines that settle it in your report.
2. **Outcome-table row coverage.** For every row of DESIGN §2.4 (plus
   the worker's added rows: `RaftRetryFailureException`,
   harness-timeout, ERR-reply), name the unit test that exercises it
   and confirm real Ratis exception types are used where constructible
   (the report should say which needed stand-ins — spot-check those).
3. **Integration test goes through the real stack.** Confirm the
   in-JVM test drives `ratis-jepsen.client`'s jepsen `invoke!` path and
   `ratis-jepsen.outcome` (not raw interop side-channels), that the
   servers-down write genuinely classifies `:info`, and run the
   integration suite **three times** for flakiness (fixed ports are in
   play — note collision behavior).
4. **Contract fidelity, both directions.** `env-contract` values vs
   DESIGN §2.6 line-by-line (nodes, port, paths, startup regex — the
   regex must match the §2.6 line and reject near-misses; check the
   negative tests exist). Also: the group UUID in the harness must
   equal the SUT's compiled constant — verify against the `sut/`
   source, not the worker's claim.
5. **`db.clj` correctness by inspection + unit tests.** It cannot be
   cluster-verified until Job 04, so scrutinize the pure parts hard:
   install command handles the versioned tarball name robustly (glob),
   peers string matches the contract form
   (`n1=n1:6000,...,n5=n5:6000`), start redirects stdout to the
   contract log path and awaits the startup regex with a bounded
   deadline, kill/wipe target exactly the contract paths (an unquoted
   or wrong `rm -rf` path here is a blocking finding), `LogFiles`
   returns the contract log.
6. **deps.edn hygiene.** SUT dependency confined to the `:test` alias
   (`:run` must not require the locally-installed snapshot); jepsen
   pinned to an exact 0.3.x version (not RELEASE/latest floating).

## Probe suggestions (pick at least one)

- Feed the classifier a hand-built `RaftRetryFailureException` wrapping
  a `TimeoutIOException` (construct via whatever public surface exists)
  — does the verdict match what emphasis 1 concluded is sound?
- In the in-JVM cluster, kill **two of three** servers (quorum loss,
  servers alive) and invoke a write — verdict should be `:info` or
  `:fail` per your emphasis-1 analysis, and the run must not hang past
  the harness timeout.
- Malformed op values (nil key, non-long value) through `invoke!` —
  harness bug surfaces loudly rather than as a silent `:fail`.

## Out of scope

Env/Docker concerns (Review 02), workloads/nemeses/checkers (Job 04),
CI, performance.

Deliver `reviews/03-harness-core/03_report.md` per `reviews/README.md`;
verdict PR titled `Review 03: <verdict>`; self-merge it if report-only.
