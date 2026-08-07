# Job 12 report — M5: version matrix and mixed-version topology

## Summary

The harness now runs against an arbitrary Ratis version end to end:
`--ratis-version` selects a version-stamped SUT tarball and `env/run.sh`
matches the harness's own `ratis-client` stack to it at launch, with an
in-harness guard that refuses skewed classpaths; `--mixed-version
OLD,NEW` runs a group with both versions installed per node (static
split or a scripted rolling upgrade under load). The full suite ran
green at 3.3.0 with no behavioral difference from 3.2.2 in any
scenario, the three banked upstream candidates re-probed as **BACKLOG 7
persists, BACKLOG 8 fixed, BACKLOG 9 persists**, and all three
mixed-version runs (partition, crash, rolling upgrade 3.2.2→3.3.0) met
their pre-committed expectations. Two decisions to look hardest at:
(1) **3.3.0 is not actually released** — the brief's premise didn't
hold, so every "3.3.0" result here is against the **RC2 staging
artifacts** (byte-verified against the dev-area vote artifacts; see
Deviations); (2) the BACKLOG 7 re-probe is an **in-JVM library probe**
on a deliberately naive `BaseStateMachine` subclass (the fixed SUT
would mask the trap), living in a new `harness/probe/` source root —
its validity is established by reproducing the Job 08 conviction at
3.2.2 before rendering the 3.3.0 verdict.

## What was built

- `sut/ratis-kv/pom.xml` — assembly tarball name now carries the Ratis
  version (`ratis-kv-<v>-ratis-<ratis.version>.tar.gz`, so versions
  coexist in `target/`); new `extra-ratis-repo` profile activated by
  `-Dratis.repo.url` for artifacts not on Central. **No source
  changes** (version plumbing only, per ownership).
- `harness/src/ratis_jepsen/db.clj` — `tarball-name-pattern` /
  `select-tarball` / `find-tarball!` select by version;
  mixed-version install (`install-mixed!`: both versions unpacked under
  `/opt/ratis-kv-versions/<v>`, contract `/opt/ratis-kv` becomes a
  symlink to the active one — the process contract and every existing
  start/kill path are untouched, and single-version installs are
  byte-identical to before); `switch-version!`, `active-version!`;
  `RatisKvDB` gains `ratis-version`/`mixed-versions` (old constructor
  arities preserved, defaulting to the 3.2.2 pin).
- `harness/src/ratis_jepsen/core.clj` — `--ratis-version` (default
  `db/default-ratis-version` = 3.2.2), `--mixed-version OLD,NEW`,
  `--roll-calm-s`, `--roll-gap-s`; `classpath-ratis-client-version` +
  `check-client-version!` (the version-skew guard);
  `mixed-version-kinds` validation (mixed refuses membership/durability
  kinds — untested interactions fail loudly); `initial-version-map`
  (static ceil(n/2)-old split / all-old for rolling); the test map
  records `:ratis-version`, `:mixed-versions`, `:version-state` and
  `:harness-ratis-client`; the run name gains `-ratis-<v>` /
  `-mixed-<old>-<new>` so stores key on the version.
- `harness/src/ratis_jepsen/nemesis.clj` — `rolling-upgrade` kind: the
  `:roll` action (kill → `switch-version!` → start → await a NEW
  contract startup line, next still-old voter in contract order),
  `rolling-nemesis`, `roll-target`, `rolling-upgrade-script`,
  `default-rolling-cycle` (calm 30 s, gap 25 s, both CLI-overridable);
  `:roll` registered in `action-fs` (one voter down at a time — no heal
  op; the op blocks through its own downtime).
- `harness/src/ratis_jepsen/checker.clj` — `rolling-upgrade-evidence`:
  a dedicated rolling run must prove every voter rolled old→new and
  came back (`roll-results`, `rolling-upgrade-verdict`; pure,
  unit-tested).
- `harness/src/ratis_jepsen/workload/register.clj` — composes the new
  evidence checker (required only for the rolling kind).
- `harness/probe/ratis_jepsen/probe/lifecycle.clj` — the BACKLOG 7/8
  library probe: in-JVM `RaftServer`s on a naive `BaseStateMachine`
  proxy that does **not** override `pause()` (phase A: the direct
  pause check; phase B: the `GroupInfoReply.getConf()` one-call wire
  check; phase C: the full live install chain — stop follower, term
  bump via transferLeadership, snapshot+purge, restart → install — and
  observe whether the receiving division survives).
- `harness/deps.edn` — `:probe` alias (probe source root +
  `ratis-server` extra-dep); comments documenting that the client
  version is launcher-owned.
- `harness/test/ratis_jepsen/{db,core,nemesis,checker}_test.clj` —
  versioned tarball selection (incl. regex-quoting and coexisting
  versions), `initial-version-map`, classpath parse + skew guard,
  `roll-target` + script shape + `action-fs`, rolling verdict
  decisions. `core_test.clj` is new; 115 tests / 995 assertions green.
- `env/run.sh` — `parse_test_versions` (scans the pass-through args),
  `ensure_tarball` per needed version (builds inside control with
  `-Dratis.version` + optional `-Dratis.repo.url`), `build_sdeps`
  (`-Sdeps :override-deps` pinning ratis-client/-grpc/
  -metrics-default/-server to the run's client version + optional
  `:mvn/repos`), new `probe` subcommand, `RJ_RATIS_REPO_URL` knob
  (validated before splicing); mixed runs put the client on OLD.
- `env/README.md` — `RJ_RATIS_REPO_URL` row in the knobs table.
- `.github/workflows/jepsen.yml` — see the itemized diff under
  criterion 5 below.
- `docs/RUNS.md` — the M5 entry: baseline, the 3.3.0 table, probe
  verdicts, mixed table, and the scenario × version × outcome
  comparison table.

## How it was verified

All cluster runs on the 8-container Docker topology (4-core x86_64
host), stores under `store/` (bind-mounted, gitignored); ledger entry
`docs/RUNS.md` "2026-08-07 — M5 gates" carries the full tables. Every
3.3.0-bearing command ran with
`RJ_RATIS_REPO_URL=https://repository.apache.org/content/repositories/orgapacheratis-1182/`.

**Criterion 1 — suites green; a 3.2.2 run reproduces its ledger entry.**

Unit suites at both versions:

```
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml verify
[INFO] Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml verify -Dratis.version=3.3.0 \
    -Dratis.repo.url=https://repository.apache.org/content/repositories/orgapacheratis-1182/
[INFO] Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
$ cd harness && clojure -M:test
Ran 115 tests containing 995 assertions.
0 failures, 0 errors.
```

Baseline through the new parameterization:

```
$ env/run.sh test --nemesis partition --time-limit 300 --ratis-version 3.2.2
...
Everything looks good! ヽ(‘ー`)ノ     # exit 0
```

313 s wall, 1.2 s analysis, 1118 / 378 / 4 (ok/fail/info), store
`ratis-kv-register-partition-ratis-3.2.2/20260807T134626.186Z` —
matching the ledgered partition shape (M1 rows 1089/408/3 etc.). Both
version tarballs coexist and are selected exactly:

```
$ ls sut/ratis-kv/target/*.tar.gz
ratis-kv-0.1.0-SNAPSHOT-ratis-3.2.2.tar.gz
ratis-kv-0.1.0-SNAPSHOT-ratis-3.3.0.tar.gz
```

The version-skew guard, negative arm (mismatched launch bypassing
run.sh's dep matching):

```
$ docker compose ... exec control clojure -M:run test ... --ratis-version 3.3.0
clojure.lang.ExceptionInfo: version skew: this JVM runs ratis-client 3.2.2
  but the run wants 3.3.0 — launch through env/run.sh test ...   # nonzero exit
```

**Criterion 2 — every 3.3.0 run of deliverable 2, with outcomes.**

`env/run.sh test --nemesis <kind> --time-limit 300 --ratis-version
3.3.0` per row (counter with `--workload counter`; listener probe
`--time-limit 180`); the sweep's per-run exits, verbatim:

```
reg-partition exit=0 wall=323s      1093/407/0
reg-crash exit=0 wall=316s          1092/408/0
reg-mixed-all exit=0 wall=316s      1104/396/0
counter-crash exit=0 wall=315s      1991/0/66, retries 459/181 ops
snapshot-churn exit=0 wall=313s     1116/384/0, 1 install event, receiver served
membership exit=0 wall=322s         1097/403/0, 21 conf transitions
unsync-drop exit=0 wall=318s        1117/383/0, 14 clear-cache acks
listener-probe exit=0 wall=76s      1112/388/0 (probe wedge: below)
```

All checkers `:valid? true`; analysis 0.9–1.5 s per run; `:info` sanity
spot-checked on the counter run (66 of 66 `:info` adds inside the ten
kill windows +6 s completion tail). Snapshot-churn's install pair at
3.3.0, verbatim from n5/n4 logs (the restructured
`SnapshotInstallationHandler` path):

```
n5@...->n4-GrpcLogAppender: followerNextIndex = 878 but logStartIndex = 1004,
  send snapshot SingleFileSnapshotInfo(t:2, i:1359)... to follower
n4@...: receive installSnapshot: n5->n4#0-t2,chunk:3ae433d6-...
n4@...: successfully install the entire snapshot-1359
```

(zero `IllegalStateException` in any node log — the SUT's Job 08
lifecycle discipline carries to 3.3.0's install path unchanged).

**Criterion 3 — all three candidate re-probes answered with evidence.**

`env/run.sh probe --ratis-version <V>` (both versions, exit 0; PROBE
lines verbatim) plus the source diff of the RC2 sources jars against
3.2.2:

- **BACKLOG 7 — persists at 3.3.0.**

  ```
  PROBE ratis-server-on-classpath=3.2.2
  PROBE phase=A sm-state-before-pause=NEW sm-state-after-pause=NEW base-pause-reaches-paused=false
  PROBE phase=C install-outcome=died follower-final={:alive false, :division "CLOSED", :sm "NEW", ...}
  PROBE ratis-server-on-classpath=3.3.0
  PROBE phase=A sm-state-before-pause=NEW sm-state-after-pause=NEW base-pause-reaches-paused=false
  PROBE phase=C install-outcome=died follower-final={:alive false, :division "CLOSED", :sm "NEW", ...}
  ```

  The 3.2.2 arm reproduces the Job 08 conviction (probe validity); the
  3.3.0 arm behaves identically: a state machine relying on the shipped
  base class still has its division killed by its first streamed
  install. Source: `BaseStateMachine.pause()` byte-identical empty;
  `StateMachineUpdater.reload()` still
  `Preconditions.assertTrue(... == PAUSED)`; the install path moved to
  `SnapshotInstallationHandler` (append-to-temp, publish on done) but
  still runs `stateMachine.pause(); state.reloadStateMachine(...)`.
  The no-backoff secondary also persists: with the division dead, the
  leader logged 90 `ServerNotReadyException` + 52 failed-append traces
  in a 30 s window at 3.2.2 and **186 + 100** at 3.3.0.

- **BACKLOG 8 — fixed at 3.3.0.** The one-call check over real gRPC:

  ```
  3.2.2:  PROBE phase=B conf-present=false conf=empty
  3.3.0:  PROBE phase=B conf-present=true conf="peers { id: \"p1\" ... startupRole: FOLLOWER } "
  ```

  Source: 3.3.0's `ClientProtoUtils.toGroupInfoReplyProto` adds
  `reply.getConf().ifPresent(conf -> b.setConf(conf))`. Upstream
  framing flips to "fixed in 3.3.0; here is the regression evidence".
  (The harness keeps its log-line conf census: it must work on 3.2.2.)

- **BACKLOG 9 — persists at 3.3.0.** Job 08's probe sequence re-run
  (`--nemesis listener-probe` at 3.3.0): staging, promotion, demotion
  and removal all commit (n7's own log adopts conf index 892 as
  listener), but the division never leaves STARTING — targeted
  linearizable reads at n7 fail both as listener and ~12 s after
  promotion to voter:

  ```
  :targeted-read [:error "...ServerNotReadyException"
    "n7@group-ABBC16E54704 is not in [RUNNING]: current state is STARTING"]
  ```

  Source: `checkStaging` (the FOLLOWER-only `containsInConf(id)`
  caught-up mark, RaftServerImpl's `!proto.getInitializing()`
  STARTING→RUNNING gate) is byte-identical at 3.3.0.

**Criterion 4 — mixed-version runs incl. the rolling sequence,
expectations in advance and judged.**

Expectations committed at `687e4dc` **before** the runs; all three met:

```
mv-partition exit=0 wall=318s      1079/417/4   (n1–n3 @3.2.2, n4–n5 @3.3.0)
mv-crash     exit=0 wall=317s      1119/370/11  (same split)
rolling-upgrade exit=0 wall=179s   1083/417/0
```

The rolling run's evidence: 5/5 rolls `:await :started`, none failed,
none missing; the recorded version map walks all-old → all-new, e.g.
after the third roll:

```
{:node "n3", :from "3.2.2", :to "3.3.0", :await :started, :active "3.3.0",
 :versions-now {"n1" "3.3.0", "n2" "3.3.0", "n3" "3.3.0", "n4" "3.2.2", "n5" "3.2.2"}}
```

Each new-version node opened the old version's raft storage in place
(RECOVER); linearizability + liveness held through every intermediate
mix; the run's tail was the 3.2.2 client against an all-3.3.0 cluster.
The 179 s wall is benign (fault-free ops run fast; the finite roll
script and the 1500-op budget both exhaust before the limit). The
mixed install layout was verified live mid-run
(`readlink /opt/ratis-kv` → `/opt/ratis-kv-versions/3.2.2` on n1,
`.../3.3.0` on n4, both trees present on every node).

**Criterion 5 — comparison table present in the ledger.**

`docs/RUNS.md` ("2026-08-07 — M5 gates") ends with the scenario ×
version × outcome table (9 scenario rows + the two probe rows; every
cell a real run). Excerpt:

```
| register + partition | GREEN (M0 + today's baseline) | GREEN | GREEN (static mix) |
| rolling upgrade 3.2.2→3.3.0 | — | — | GREEN, 5/5 rolled |
| Library probe: GroupInfoReply conf (BACKLOG 8) | dropped (empty) | populated (fixed) | — |
```

**Criterion 6 — established reporting.** Analysis times all 0.9–1.5 s
(comfortably inside budget); `:info` sanity above; ownership respected
(files listed in "What was built" only); Apache-2.0 headers on the new
probe and test files; this report.

**CI (deliverable 5) — the itemized diff** (`.github/workflows/jepsen.yml`):

1. New `workflow_dispatch` inputs: `ratis-version` (default `'3.2.2'`),
   `mixed-version` (default `''`, `OLD,NEW` enabling the mixed tokens),
   `ratis-repo-url` (default `''`, reaches the SUT build as
   `-Dratis.repo.url` and the harness as `RJ_RATIS_REPO_URL`).
2. `build-sut`: validates the version inputs as plain tokens (they are
   spliced into commands), **fails fast when scenarios include a
   mixed token with an empty `mixed-version`**, and builds one tarball
   per distinct version (`ratis-version` ∪ the mixed pair); new job
   outputs `ratis-version`/`mixed-version`; the upload glob is
   unchanged (all tarballs land in the one artifact).
3. `test` scenario translation gains `mv-partition`/`mv-crash` →
   `--nemesis <kind> --mixed-version "$MIXED"` and `rolling-upgrade`;
   every other token now passes `--ratis-version`; `RJ_RATIS_REPO_URL`
   exported for run.sh.
4. `red-gate` runs at the dispatched version.
5. Defaults preserved: scenario list, time-limit handling, store
   artifacts, concurrency and retention untouched; a plain dispatch is
   behavior-identical to before (the explicit `--ratis-version 3.2.2`
   it now passes was already the default).

Verified by YAML parse and by exercising the identical run.sh
commands the workflow generates (all the runs above); not exercised by
a live dispatch from this branch (see Known gaps).

## Deviations from the brief

1. **The brief's premise "3.3.0 has since been released" does not hold
   as of 2026-08-07.** Checked from this environment: Maven Central
   404s the artifacts and its metadata tops out at 3.2.2 (the mirror
   is provably fresh — junit metadata updated the same day);
   `downloads.apache.org/ratis` has no 3.3.0;
   `dist.apache.org/repos/dist/dev/ratis/3.3.0/` holds `rc1/` and
   `rc2/`. Smallest reasonable interpretation, per the brief's own
   intent ("add 3.3.0 to the matrix the week it ships" / RC2 named in
   the brief): the matrix ran against the **RC2 staging artifacts**
   (Maven staging repo `orgapacheratis-1182`, version string `3.3.0`),
   with provenance verified — the staging `ratis-server-3.3.0.jar` is
   byte-identical (sha512) to the jar inside the dev area's
   sha512-verified `apache-ratis-3.3.0-bin.tar.gz`. Everything labeled
   3.3.0 in this job means those bits — exactly what will ship if the
   vote passes, but the release could still change or be abandoned.
   The staging URL is a run-time input (`RJ_RATIS_REPO_URL` /
   `ratis-repo-url`), never hardcoded, because staging repos are
   dropped after promotion; once 3.3.0 reaches Central the same
   commands work with the knob unset.
2. **The rolling-upgrade nemesis vs the "no new nemeses" non-goal**:
   deliverable 4 explicitly requires the rolling sequence, so I read
   the non-goal as "no new fault kinds beyond the deliverables" and
   implemented `:roll` as an action (not a fault) in the established
   nemesis framework.
3. **A probe source root under `harness/`** (`harness/probe/`, loaded
   only by the `:probe` alias): the brief's "probe the library
   behavior deliberately" requires a state machine that does NOT carry
   the SUT's lifecycle fix, and `sut/**` was version-plumbing-only —
   so the naive SM lives harness-side. `sut/**` indeed carries no
   behavioral change.
4. **Store naming**: run names (and so store directories) now carry the
   version (`-ratis-<v>`/`-mixed-<old>-<new>`). The baseline therefore
   reproduces its ledger entry's *behavior* under a new directory name;
   old stores are unaffected.

## Known gaps and risks

- **RC2 is not a release.** If the vote fails or an rc3 differs, the
  3.3.0 column describes bits that never shipped. The knob design makes
  re-running the matrix against any successor a parameter change; the
  probes are the fastest re-check (≈40 s each).
- The CI changes are validated by YAML parse + running the exact
  commands the workflow generates, not by a live `workflow_dispatch`
  from this branch. First dispatch after merge should eyeball the
  build-sut tarball listing (one per version) and an `mv-*` token.
- Mixed-version composes only with
  `none|partition|crash|pause|mixed|transfer|rolling-upgrade`
  (enforced with a clear error). Membership/durability × mixed are
  future work by design, not silent gaps.
- The skew guard identifies the client version from the classpath jar
  path; a repackaged/uberjar deployment would fall back to a loud
  warning instead of a hard refusal (first-match-wins on classpath
  order is unit-test-pinned).
- The probe's phase C observes division death via the in-JVM Division
  handle plus a targeted read; it does not scrape lifecycle logs, so a
  hypothetical future version that closes the division *without*
  CLOSING state would need the assertions revisited.
- The rolling script's budget math: at very small `--time-limit` the
  op budget can exhaust before all five rolls and the run fails with
  `:incomplete-rolling-upgrade` (by design, with the remedy named in
  the error).

## Appendix — the mixed-version expectations as pre-committed

Committed verbatim at `687e4dc` ("Job 12: mixed-version expectations on
record before the runs"), before any mixed run executed; reproduced
here so this report is self-contained. All three were met.

1. **register + partition, static mixed — expect GREEN** (linearizable,
   live, `:info` only inside fault windows). Raft wire compatibility
   within a minor release line is an explicit upstream norm; the 3.3.0
   sources show no appendEntries/requestVote/installSnapshot proto
   field removals. What a failure would look like if the expectation
   is wrong: cross-version append/vote rejections → mass `:info`,
   election storms, liveness red; semantic drift → a linearizability
   conviction. *(Result: GREEN, 1079/417/4.)*
2. **register + crash, static mixed — expect GREEN.** Crash-restart
   additionally exercises RECOVER of each version's own storage plus
   cross-version log catch-up after restarts. *(Result: GREEN,
   1119/370/11.)*
3. **rolling upgrade — expect GREEN and complete**: all five `:roll`
   ops return `:await :started` (the new version opens the old
   version's raft storage in place), the rolling-evidence checker
   reports 5/5 rolled, and linearizability + liveness hold through
   every intermediate mix from 5-old to 5-new; after the last roll the
   3.2.2 client runs against an all-3.3.0 cluster. We additionally
   expected the cluster to serve during each roll (one voter down at a
   time; elections inside the liveness window). *(Result: GREEN,
   1083/417/0, 5/5 rolled, no liveness flag.)*

## Suggestions (out of scope)

- **Update BACKLOG 7/9 upstream framing before filing**: file against
  3.3.0 too (both reproduce there), and BACKLOG 8's item flips to
  "fixed in 3.3.0 — regression evidence available" (the probe's phase
  B is the ready-made regression check). The probe now gives a ~40 s
  repro command for the pause trap on any version — worth attaching to
  the upstream issue.
- A `mv-` counter run (exactly-once across a mixed group) would extend
  the matrix cheaply once wanted.
- When 3.3.0 (or its successor) reaches Central, add a one-line CI
  dispatch preset in the README and retire the staging-URL example.
- The harness README (`harness/README.md`) still describes the Job 03
  state (noRetry, "no workloads yet"); it predates several jobs and
  was left untouched here (outside the smallest-change principle) —
  worth a docs pass in some future polish job.
