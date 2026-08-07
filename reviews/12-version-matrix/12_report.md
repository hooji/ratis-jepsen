# Review 12 report — M5: version matrix and mixed-version topology

Worker PR #25, head `claude/version-matrix-brief-7zsh85` @ `f9be19c`,
reviewed against `jobs/12-version-matrix/12_brief.md` with the Review 12
brief's emphases. Verified in a fresh 8-container Docker topology
(4-core x86_64), worker branch consumed read-only via
`git worktree add ../job-12-under-review FETCH_HEAD`.

## Verdict: MERGE

## Justification

Every acceptance criterion reproduced independently, including the two
the review brief flagged as load-bearing: the in-JVM library probe's
3.2.2 control arm re-produces the Job 08 conviction in my own run —
same `StateMachineUpdater.reload():230` PAUSED-precondition
`IllegalStateException`, division dead ~4 ms after a "successfully
install the entire snapshot" — and the RC2 arm behaves identically, so
**BACKLOG 7 persists** on the naive base class while my snapshot-churn
run proves the fixed SUT survives the same path. **BACKLOG 8 is
genuinely fixed** at RC2 (populated conf over real gRPC, the one-line
source change located and quoted correctly, workaround still functional
and needed for 3.2.2, no version branch). **BACKLOG 9 persists** —
all four conf transitions commit while n7 refuses reads in STARTING,
mechanism source-identical. The RC2 provenance chain is exactly as the
worker states and I reproduced the byte-verification; every
results-bearing surface labels 3.3.0 as RC2/not-released. Mixed-version
runs met pre-committed expectations (the git record proves expectations
carried zero outcomes), the rolling upgrade demonstrably restarts every
voter onto the other version under load (node-state evidence below,
including open-fd proof of which jars each live JVM runs), both wire
directions carried committed appends, and the skew guard hard-refuses a
deliberately mismatched launch before touching any node. Both review
probes passed: a **reverse rolling downgrade 3.3.0→3.2.2 ran green**
(new coverage — 3.2.2 recovering RC2-written storage), and a
nonexistent version fails in 4.6 s with a self-explanatory error.
Zero blocking findings.

## What I verified

Environment accommodations (uncommitted, same class as Reviews 08–11):
slim 6-cert proxy-CA bundle via `RJ_EXTRA_CA_BUNDLE` (the session's
full bundle exceeds run.sh's 64 KiB cap — the cap's error message is
exactly right); `apt-get install git` inside `control` plus
`mvnw -q install` of the SUT jar to run the harness unit suite there
(see finding 1). Every 3.3.0 command ran with
`RJ_RATIS_REPO_URL=https://repository.apache.org/content/repositories/orgapacheratis-1182/`.

### Emphasis 1 — artifact provenance and RC2 labeling

Release status, checked from scratch (not from the worker's evidence):

```
$ curl repo1.maven.org/.../ratis-server/maven-metadata.xml   → <latest>3.2.2</latest>
$ curl -w %{http_code} .../ratis-server/3.3.0/ratis-server-3.3.0.jar → 404
$ curl downloads.apache.org/ratis/      → 3.0.1/ 3.1.3/ 3.2.1/ 3.2.2/ (no 3.3.0)
$ curl dist.apache.org/repos/dist/dev/ratis/3.3.0/           → rc1/ rc2/
```

Byte-verification reproduced end to end: the rc2 vote tarball's
sha512 matches its `.sha512` (`f9c21ba8…e7c4bcb0`); the staging repo's
`ratis-server-3.3.0.jar` is byte-identical to the jar inside that
verified tarball:

```
34eaab0a…fd7d64  ratis-server-3.3.0.jar                        (staging orgapacheratis-1182)
34eaab0a…fd7d64  apache-ratis-3.3.0-bin/jars/ratis-server-3.3.0.jar  (sha512-verified vote artifact)
```

Surface audit: `grep -rn "3\.3\.0"` over `harness/ env/ sut/ .github/`
finds exactly one non-test mention — the workflow's `mixed-version`
input example (`e.g. 3.2.2,3.3.0`), a syntax illustration sitting next
to the `ratis-repo-url` input that spells out the staging/RC story. No
code hardcodes the staging URL (runtime knob only, verified in pom
profile + run.sh + workflow). The ledger's M5 section headlines
"3.3.0 RC2", states "**3.3.0 is NOT a completed release as of
2026-08-07**" with the full evidence chain, and labels the run table
"@3.3.0 RC2" and the comparison column "3.3.0 RC2"; the report's
summary leads with the same. A few interior lines say bare
"3.3.0→3.2.2"-style tokens, but always under RC2-labelled headers —
I judge no surface reads as a released version. **No blocking
documentation defect.**

### Emphasis 2 — the in-JVM probe: both arms re-run, validity judged

`env/run.sh probe --ratis-version <V>`, both versions, exit 0:

```
PROBE ratis-server-on-classpath=3.2.2
PROBE phase=A sm-state-before-pause=NEW sm-state-after-pause=NEW base-pause-reaches-paused=false
PROBE phase=B conf-present=false conf=empty
PROBE phase=C t=5  follower-alive=true  follower-division=RUNNING …
PROBE phase=C t=10 follower-alive=false follower-division=CLOSED  …
PROBE phase=C install-outcome=died follower-final={:alive false, :division "CLOSED", :sm "NEW", …}

PROBE ratis-server-on-classpath=3.3.0
PROBE phase=A … base-pause-reaches-paused=false
PROBE phase=B conf-present=true conf="peers { id: \"p1\" address: \"127.0.0.1:33085\" startupRole: FOLLOWER } "
PROBE phase=C install-outcome=died follower-final={:alive false, :division "CLOSED", :sm "NEW", …}
```

The control arm's kill mechanism, verbatim from my probe log — the
receiving division dies of the exact Job 08 chain, not merely "a death":

```
15:22:09.167 SnapshotInstallationHandler -- c2…: receive installSnapshot: c3->c2#0-t4,…
15:22:09.197 ERROR StateMachineUpdater -- c2…-StateMachineUpdater caught a Throwable.
java.lang.IllegalStateException: null
    at org.apache.ratis.util.Preconditions.assertTrue(Preconditions.java:35)
    at org.apache.ratis.server.impl.StateMachineUpdater.reload(StateMachineUpdater.java:230)
15:22:09.201 SnapshotInstallationHandler -- c2…: successfully install the entire snapshot-166
```

Identical stack at RC2 (via the restructured `SnapshotInstallationHandler`
path). No-backoff secondary reproduced: my logs count 95
`ServerNotReadyException` + 60 failed-append traces at 3.2.2 and
**192 + 102** at RC2 across the 30 s watch (worker: 90+52 / 186+100 —
same magnitudes). Source cross-checked from the Central 3.2.2 and
staging RC2 sources jars: `BaseStateMachine.pause()` empty and
byte-identical at both; `reload()`'s PAUSED assert identical;
`BaseStateMachine.initialize` never transitions the LifeCycle (so the
probe's NEW states are exactly what source predicts); RC2's install
path moved into `SnapshotInstallationHandler` (append-to-temp,
`pause()` at line 236 right before publishing) and still requires the
PAUSED transition the base class never makes.

**Representativeness — judged: representative.** The naive SM is
structurally the upstream `CounterStateMachine` example: extends
`BaseStateMachine`, uses `SimpleStateMachineStorage`, overrides
`initialize`/`reinitialize`/`takeSnapshot`/`query`/`applyTransaction` —
and `pause` appears **zero times** in the shipped RC2
`ratis-examples` `CounterStateMachine.java` (checked in the staging
sources jar). An integrator following upstream's own example inherits
exactly this trap; nothing in the probe is shaped to fail. The probe's
aggressive snapshot/purge settings only make the install path reachable
quickly — they don't create the trap. The complementary arm: my
snapshot-churn run at RC2 (below) shows the *fixed* SUT surviving the
same install path with zero `IllegalStateException` across node logs —
the probe measures the library default, the suite measures our SUT, and
they differ exactly as claimed.

### Emphasis 3 — BACKLOG 8 fixed, and the consequences

Phase B above: at 3.2.2 `conf-present=false`; at RC2
`conf-present=true` with the actual peer list — a populated field over
real gRPC, not a differently-shaped absence. Source: RC2
`ClientProtoUtils.toGroupInfoReplyProto` adds
`reply.getConf().ifPresent(conf -> b.setConf(conf));` (my diff of the
sources jars confirms the worker's quote verbatim; absent at 3.2.2).

Workaround consequences, verified live at RC2: the log-line conf census
**still functions** — my listener-probe run's censuses parsed n7's
adopted confs from its own log (`{:index 895, :servers [n1–n5],
:listeners [n7]}` → `{:index 1959, :servers […n7], :listeners []}`),
and my membership run's census-driven evidence checker counted **20
committed conf transitions** (worker: 21) and passed. It is **still
needed** (the harness must work at 3.2.2) and there is **no
version-dependent branch** — the harness uses the census on every
version, stated plainly in the worker's report. For the BACKLOG 8
rewrite the report supplies: what fixed it (the quoted source line),
where proven (probe phase B, both versions), the ready-made regression
check (`run.sh probe` phase B), and the RC2 caveat. Sufficient to
rewrite the entry accurately.

### Emphasis 4 — BACKLOG 9 re-verified at RC2

`run.sh test --nemesis listener-probe --time-limit 180
--ratis-version 3.3.0` → exit 0. From my run's history: staging,
promotion, demotion and removal **all committed** (`:success? true`
each; n7's own log adopted the listener conf at index 895 and the
voter conf at 1959), while both targeted linearizable reads at n7
failed:

```
:targeted-read [:error "…ServerNotReadyException"
  "n7@group-ABBC16E54704 is not in [RUNNING]: current state is STARTING"]   ×2
```

— once as listener, once after promotion. Same symptom as Job 08 at
3.2.2; mechanism confirmed unchanged in source (`LeaderStateImpl.
checkStaging()`'s caught-up filter is the FOLLOWER-only
`containsInConf(f.getId())` at both versions — 3.2.2:846, RC2:885).
**Persists**, with the same rigour as the original.

### Emphasis 5 — mixed-version runs and the rolling upgrade

**Expectations before outcomes, from the git record:** commit
`687e4dc` ("mixed-version expectations on record before the runs",
13:58:09Z) adds 46 lines containing **zero** outcome text (`(Result:`
count = 0); outcomes appear only in `f9be19c` (14:52:51Z), with
`687e4dc` an ancestor. The worker's mixed stores timestamp 14:35–14:46Z,
after the expectations commit. Confirmed.

**My rolling upgrade re-run** (`--nemesis rolling-upgrade
--mixed-version 3.2.2,3.3.0 --time-limit 300`): exit 0, 1500 ops
1138/362/0, every checker valid; rolling evidence **5/5 applied, 0
failed, 0 skips, 0 missing**; the history's `:versions-now` walks
all-3.2.2 → all-3.3.0 one voter at a time. Node-state evidence,
collected by my own watcher polling the nodes (not the harness):

```
symlink flips (readlink /opt/ratis-kv):  n1 15:57:03  n2 15:57:33  n3 15:57:59  n4 15:58:24  n5 15:58:54
n1 log: "ratis-kv server started" at 15:56:30 (3.2.2 boot) and 15:57:03 (3.3.0 boot), same storage=/var/lib/ratis-kv
startup lines: exactly 2 per node (all five)
```

**Both wire directions carried committed appends**, from the leader
timeline in node logs: n3 (still 3.2.2) took leadership at 15:56:32 and
served while n1/n2 rolled to 3.3.0 (old-leader→new-follower); when n3's
roll killed it, **n1 — already on 3.3.0 — became leader at 15:57:58
with n4/n5 still on 3.2.2** (new-leader→old-follower) until 15:58:54;
ops committed throughout (liveness green, 1138 ok). My reverse-pairing
probe run (below) shows the same two directions with the roles swapped.

**Static split** (`--nemesis partition --mixed-version 3.2.2,3.3.0`):
exit 0, 1090/409/1, all checkers valid. Mid-run node state, polled
live: n1–n3 `readlink` → `…/3.2.2`, n4–n5 → `…/3.3.0` — the ceil(5/2)
split as specified — and process-level proof of what actually runs:

```
n1 (pid 4807) /proc/<pid>/fd: 13 open fds into /opt/ratis-kv-versions/3.2.2/… incl. ratis-server-3.2.2.jar
n4 (pid 4298) /proc/<pid>/fd: 12 open fds into /opt/ratis-kv-versions/3.3.0/… incl. ratis-server-3.3.0.jar
```

Client placement verified from the run logs' test maps:
`:harness-ratis-client "3.2.2"` on mixed runs (clients upgrade last),
`"3.3.0"` on the reverse pairing — matching the skew-guard contract.

### Emphasis 6 — the skew guard refuses, tested deliberately

Bypassing run.sh's dep matching (plain `clojure -M:run test …
--ratis-version 3.3.0` on the default 3.2.2 classpath):

```
clojure.lang.ExceptionInfo: version skew: this JVM runs ratis-client 3.2.2
  but the run wants 3.3.0 — launch through env/run.sh test (it injects
  matching client deps via -Sdeps), or pass a matching --ratis-version/--mixed-version
exit=255
```

No SUT process existed on any node afterwards (`pgrep -x java` → none)
— the guard sits in test-map assembly, before any node contact. A
silent skew cannot happen through either entry point; the uberjar
fallback (warn, not refuse) is documented and unit-test-pinned.

### Acceptance criteria 1, 2, 5, 6

**AC1 — suites green; 3.2.2 reproduces its ledger entry.**

```
mvnw verify                                  → BUILD SUCCESS (51 tests)   [3.2.2]
mvnw verify -Dratis.version=3.3.0 -Dratis.repo.url=<staging>
                                             → Tests run: 51, F0 E0 — BUILD SUCCESS
clojure -M:test                              → Ran 115 tests containing 995 assertions. 0 failures, 0 errors.
run.sh test --nemesis partition --ratis-version 3.2.2
                                             → exit 0, 1500 ops 1108/392/0, analysis 1.3 s
                                               store …-partition-ratis-3.2.2/20260807T152453.336Z
```

The baseline matches its ledger shape (worker today 1118/378/4; M1 row
1089/408/3). Both version-stamped tarballs coexist in `target/` and are
selected exactly.

**AC2 — the 3.3.0 suite.** All eight rows re-run at RC2 by me, exit 0,
every composed checker `:valid? true`, analysis 1.2–4.6 s:

| Run @RC2 (mine) | ok/fail/info | Evidence notes |
|---|---|---|
| register + partition | 1100/397/3 | — |
| register + crash | 1074/409/17 | — |
| register + mixed-all | 1093/406/1 | 1 committed conf transition observed |
| counter + crash | 2060/2/25 | counter checker valid; exactly-once held |
| snapshot-churn | 1146/354/0 | 2 installs; n3 "successfully install the entire snapshot-1309" (RC2 handler path); receiver survived; **0 IllegalStateException across node logs** |
| membership | 1138/362/0 | 20 committed conf transitions via census |
| unsync-drop (lazyfs) | 1082/418/0 | 15 clear-cache acks; lazyfs started on all five nodes |
| listener-probe | 1101/399/0 | the BACKLOG 9 wedge, above |

Counts differ within normal run-to-run variance from the worker's table
(fault windows land differently); no behavioral difference from 3.2.2
surfaced in mine either.

**AC4 — mixed runs.** mv-partition + rolling above; mv-crash (same
static split): exit 0, 1133/367/0, all checkers valid.

**AC5 — comparison table** present in the ledger
("2026-08-07 — M5 gates"), scenario × version × outcome, RC2-labelled
column, probe rows included — content matches what I reproduced.

**AC6 — established reporting.** Analysis 1.2–1.9 s on my runs (4.6 s
listener-probe — still far inside the checker budget); `:info` sanity:
baseline `:info` appeared only under faults; the reverse rolling run's
16 `:info` all landed at t=32–34 s, inside its first roll window
(31–35 s — the leader-kill roll), none elsewhere; ownership — all 17 changed
files vs the merge-base (`6b479eb`) fall inside the declared list,
`sut/**` touched only in `pom.xml` (assembly name + opt-in repo
profile — no behavioral change); Apache-2.0 headers on all new files;
no committed artifacts; `docs/RUNS.md` strictly appended (+133).
The PR base moved one commit (`42f3548`, Job 13 brief) after the worker
branched — no overlap with this diff.

**CI (deliverable 5).** Itemized diff verified line-by-line against
`jepsen.yml`: the three inputs with token validation, per-version
tarball builds (`ratis-version` ∪ mixed pair), the fail-fast for mixed
tokens without a `mixed-version` input, `mv-*`/`rolling-upgrade`
translation, red-gate at the dispatched version, defaults preserved
(a plain dispatch passes `--ratis-version 3.2.2`, already the default).
Like the worker I did not live-dispatch from the branch; I exercised
the exact generated commands. The report's known-gap note covers this
honestly.

### Review probes (beyond the worker's runs)

1. **Reverse pairing — rolling downgrade 3.3.0→3.2.2**
   (`--mixed-version 3.3.0,3.2.2 --nemesis rolling-upgrade`):
   **GREEN** — exit 0, 1086/398/16, rolling evidence 5/5, two boots
   per node. Every 3.2.2 process RECOVERed raft storage written by
   RC2 under load, linearizability + liveness held through every mix,
   and the 3.3.0 client finished against an all-3.2.2 cluster. Leader
   timeline: n4 (3.3.0) led rolled-3.2.2 followers from 16:03:15
   (new→old); n2 (3.2.2) led still-3.3.0 n5 from 16:04:41 (old→new).
   New coverage the coordinator may want banked in the ledger — the
   downgrade path needs no code change, only the reversed pair.
2. **Nonexistent version** (`--ratis-version 9.9.9`): fails in
   **4.6 s**, exit 1: `run.sh: no SUT tarball for ratis 9.9.9;
   building inside control` → Maven `Could not find artifact
   org.apache.ratis:ratis-server:jar:9.9.9 in central`. Immediate,
   comprehensible, no node touched.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking | `harness/deps.edn:61` (context) | The harness unit suite is not runnable inside the shipped `control` image as the report's `cd harness && clojure -M:test` implies: the image carries no `git` (the `:test` alias has a git dep — `io.github.cognitect-labs/test-runner`) and the classpath needs the SUT jar `mvnw install`ed first. Both pre-date Job 12; run.sh's `test` path is unaffected (its git shim + tarball flow work out of the box). Worth a README line or `git` in the image. |
| 2 | non-blocking | `env/run.sh:290` (`cmd_probe` via `parse_test_versions`) | `run.sh probe` silently ignores unknown extra arguments (only `--ratis-version`/`--mixed-version` are scanned), so a typo like `--ratis-verion 3.3.0` probes the default 3.2.2 without complaint. The probe prints `ratis-server-on-classpath=…` first, which limits the damage; still, refusing unrecognized args would be cheaper than a mislabelled probe result. |

## Required revisions

None — verdict is MERGE.

## Suggestions (non-blocking)

- Bank the reverse-pairing result: rolling **downgrade** 3.3.0-RC2 →
  3.2.2 ran green here (store
  `…rolling-upgrade-mixed-3.3.0-3.2.2/20260807T160231.469Z`-shaped in
  my workspace; numbers above). It needs no code — the pair argument
  already accepts either order — and a ledger row plus a CI example
  would document that the matrix covers both directions.
- Finding 1's remedy: add `git` to the env image (one apt token) or a
  README note on unit-suite prerequisites (`git`, `mvnw install`).
- Finding 2's remedy: have `parse_test_versions` (or `cmd_probe`)
  reject arguments it does not recognize.
- When the vote concludes, the ledger's M5 section could gain a
  one-line postscript stating whether the released 3.3.0 equals the
  RC2 bits tested (re-run `run.sh probe` against Central as the
  fastest re-check — the worker's own suggestion, endorsed).

## Verification notes

- Worker branch consumed read-only (`git worktree add
  ../job-12-under-review FETCH_HEAD`); nothing pushed to it; worktree
  removed after review.
- Docker required and used: fresh image build from the worktree's
  `env/` through the session proxy (slim CA bundle; the 64 KiB
  `RJ_EXTRA_CA_BUNDLE` pre-flight fired correctly on the full bundle
  first — its message is accurate and actionable).
- My runs' store directories live only in my workspace (gitignored);
  excerpts above are trimmed verbatim from them.
