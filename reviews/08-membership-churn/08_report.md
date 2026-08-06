# Review 08 report — M2 part 2: membership churn (the L3-orchestrator rehearsal)

## Verdict: MERGE

## Justification

Every acceptance criterion reproduced independently in my environment
(fresh 7-node topology, both suites, the full gate matrix, the probe,
and a seeded red), and — the heart of this review — **both conviction
chains verified link-by-link against the ratis-3.2.2 sources and
re-reproduced live**: the pause()/reload() division-kill on a
broken-SUT churn run in my own environment (updater crash + division
close on every install, run still green — exactly the mechanism that
retro-explains Job 07), and the staged-listener STARTING wedge
(reproduced here too — both targeted reads refused, conf mechanics all
committing). The SUT diff is minimal
and correctly itemized; the one file beyond the anticipated `--join`
mode (the `KvStateMachine` lifecycle fix) is forced by the brief's own
acceptance criterion 3 and mirrors upstream's test state machine
line-for-line. Findings below are all non-blocking.

## What I verified

Environment: Docker 8-container topology per `env/`, ratis 3.2.2,
jepsen 0.3.13, JDK 21, worker branch `claude/membership-churn-analysis-nn6hlo`
at 35c9842 in a read-only worktree. Ratis sources for the chain
verification came from the Maven Central `-sources` jars at 3.2.2
(server, server-api, common, client, grpc, plus the server test-sources
jar for the precedent classes).

### Emphasis 1 — the pause() conviction chain, end to end from source

Every link confirmed at ratis-3.2.2, at the exact cited locations:

1. `BaseStateMachine.pause()` **is an empty method** and
   `BaseStateMachine.initialize` never moves the lifecycle out of NEW
   (it only sets groupId/server/name). The `LifeCycle` predecessor map
   (`LifeCycle.java`, static block) pins `PAUSING ← RUNNING` and
   `PAUSED ← PAUSING` only — so a naive SM cannot legally reach PAUSED
   at all.
2. The streamed-install path calls `pause()` first:
   `ServerState.installSnapshot` **line 476** (`sm.pause(); // pause
   the SM to prepare for install snapshot`), then
   `SnapshotInstallationHandler` (lines 228/365) signals
   `state.reloadStateMachine(...)`.
3. `StateMachineUpdater.reload()` **line 230**:
   `Preconditions.assertTrue(stateMachine.getLifeCycleState() ==
   LifeCycle.State.PAUSED)` — the hard assert.
4. The updater run-loop's catch-all (lines ~201–209) logs
   `" caught a Throwable."` and calls `server.close()` where `server`
   is `RaftServerImpl` — **the division closes, the process survives**,
   exactly as the report's blast-radius analysis says.
5. The SUT fix mirrors upstream's own `SimpleStateMachine4Testing`
   **line-for-line in pattern**: `initialize` via
   `getLifeCycle().startAndTransition(...)` (their line 215), `pause()`
   = PAUSING→PAUSED (229–231), `reinitialize()` with the PAUSED-guarded
   STARTING→RUNNING resume (241–243).

Empirically (fixed SUT, my combined gate run): 4 completed streamed
installs, **zero** `caught a Throwable` on all seven nodes, and **n5
survived two live installs in one run** — after its second
`successfully install the entire snapshot-1066` (06:31:23), its own
`StateMachineUpdater` thread is alive a minute later taking a snapshot
at index 2676 (06:32:27), i.e. ~1600 further applies executed on the
thread that dies on the broken SUT. Division open, applies continue —
the review brief's "survives repeated live installs" observed
literally.

`KvStateMachineLifecycleTest` pins the exact pause→reinitialize
contract through a real server — and I verified it convicts: overlaying
the test onto main's pre-fix SUT fails with `initialize must leave the
state machine RUNNING ==> expected: <RUNNING> but was: <NEW>`. Red on
the broken SM, green on the fixed one; a real pin, not a tautology.

### Emphasis 2 — the retroactive reconciliation (mandatory)

I re-ran a Job 07-style churn schedule (`--nemesis snapshot-churn
--rate 1.4 --ops-per-key 800 --time-limit 300`) on the **broken** SUT —
main's `KvStateMachine`, no `pause()` override — in my environment
(the review-brief probe, run against live behavior since the preserved
Job 07 stores exist only in the worker's environment; Job 07's ledger
and Review 07's report supply the recorded observations I reconcile
against):

**The broken-SUT run went green** (exit 0, all checkers valid,
`:install-snapshot-evidence {:valid? true, :total 139}`) **while its
logs carry the whole catastrophe** — Job 07's blindness reproduced
end-to-end in one store
(`store/ratis-kv-register-snapshot-churn/20260806T064704.897Z`, my
environment, preserved):

- n2, the churned follower, timeline at 06:49:26: `.353` `SnapshotManager
  - Installed snapshot, renaming temporary dir … to …/sm` (install
  durable on disk) → `.356` `StateMachineUpdater caught a Throwable.
  java.lang.IllegalStateException at
  StateMachineUpdater.reload(StateMachineUpdater.java:230)` — the exact
  cited frame — → `.357` `RaftServer$Division: shutdown`. **Four
  milliseconds from install success to division death.**
- The leader then hammered the corpse: n2's log holds **4,876**
  `ServerNotReadyException … is not in [STARTING, RUNNING]: current
  state is CLOSED` traces (+11 CLOSING), the leaders logged **130**
  `send snapshot` re-initiations and **2,216** `Decrease nextIndex`
  bounces for ~2 real install events.
- A later churn cycle drew n2 as kill victim again: kill -9 → RECOVER
  resurrected the division from the installed snapshot → the next
  install killed it **again** at 06:51:21 (second identical
  `IllegalStateException` at reload:230) — the 100%-per-install
  reproduction *and* the kill-restart masking, both in one log.

**(a) Why Job 07 was green with install evidence on the broken
`pause()`:** all three of the review brief's suggested mechanisms are
real, and they compose:

1. **Evidence lands before the death.** Both counted patterns — the
   leader's `send snapshot` line and the follower's
   `receive installSnapshot` chunk line — are logged during transfer;
   the updater crash happens at `reload()` *after* the snapshot is
   finalized on disk. Every install in a broken-SUT run emits full
   evidence and then kills its receiver.
2. **The kill is a follower-division close, invisible to every
   checker.** Clients are leader-routed in the churn runs, so a dead
   follower division rejects nothing client-visible; the leader
   commits on the surviving majority; liveness sees progress;
   linearizability sees correct answers. Nothing examined division
   health.
3. **The churn cycle's own kill -9 masks it.** The `churn-restart`
   heal is pidfile-based, so a live process with a dead division reads
   `:already-running` and is *not* restarted — the corpse lingers —
   but a later cycle drawing the same node as `churn-kill` victim
   kill-9s the process and the RECOVER restart resurrects the division
   from the *installed* snapshot (the install is durable — it
   finalized before the crash).

So: **Job 07's greens were real but measured a narrower property than
the ledger implied** — "the install path is exercised and the snapshot
transfers and persists," not "the receiver survives an install."
The runs were not measuring receiver health, and receiver health was
the thing broken.

**(b) Which storm reading is the truth:** Job 08's. The decisive
evidence was already in Review 07's own record: its reproduced storm
traces read `ServerNotReadyException: … is not in [STARTING, RUNNING]:
current state is CLOSED`. The append/install RPC path *accepts* a
STARTING division (`RaftServerImpl.appendEntriesAsync` asserts
`STARTING_OR_RUNNING`, verified at source) — so a "still-initializing
division" **cannot** produce those rejections; only a CLOSING/CLOSED
one can, and the division that is CLOSED right after an install is the
one that install just killed. My broken-SUT run confirms the sequence
directly (see above): install completes → updater crash → division
`shutdown` → the leader's appends/installs bounce off the corpse as
SNRE-CLOSED until the next churn-kill of that node resurrects it.
"Converges cleanly" was an artifact of the nemesis's kill-9 cycle
doing the operator's restart for free; on a real deployment nothing
restarts the division and the leader hammers a corpse indefinitely.

**BACKLOG item 7 therefore needs rewriting**, roughly: primary defect
= *live streamed installs kill any `BaseStateMachine`-derived division*
(empty `pause()` vs `reload()`'s PAUSED assert + division-closing
catch-all; 100% repro); the no-backoff retry storm is a *secondary
aggravator* of the same event (unthrottled leader retries against the
corpse its own install created — Review 07's ~1.6k traces were this,
not reboot noise); severity up from "converges cleanly," since
convergence in our runs was the nemesis, not the system. The two
upstream candidates should be filed together, division-suicide first.

### Emphasis 3 — the `GroupInfoReply.getConf()` wire drop

Verified from source at 3.2.2: `GroupInfoReply` carries a
`RaftConfigurationProto conf` field and the server populates it
(`RaftServerImpl.getGroupInfo`, lines 679–685); the wire serializer
`ClientProtoUtils.toGroupInfoReplyProto` (line 359) sets rpcReply,
group, isRaftStorageHealthy, role, commitInfos and logInfo — **never
conf**; the deserializer (line 515) maps the absent field to null, so a
remote `getConf()` is always `Optional.empty()`. The proto *has* the
field (`hasConf()` is checked), making this a genuine
serializer-omission upstream candidate, exactly as reported.

Census soundness: `ServerState.setRaftConf` (line 394) logs
`set configuration` with `RaftConfigurationImpl.toString()`/
`PeerConfiguration.toString()` producing exactly the parsed shape;
`LeaderStateImpl.StartupLogEntry` (line 293) appends the **current**
conf — stable, `old=null` — at every leader startup, confirming that
counting stable lines would count elections; `applyOldNewConf` is the
only transitional producer; boot-time replays re-log the *same index*,
which dedup-by-index absorbs.

**The elections-but-no-membership probe**: on my broken-SUT
snapshot-churn store (kills/restarts force elections; zero membership
ops) — **94** `set configuration … old=null` lines across the node
logs (the elections and their startup re-appends, present in force)
and **transitional count = 0**. Elections cannot masquerade as
membership evidence.

### Emphasis 4 — `--join` mode

- `sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml verify`: **42 tests, 0
  failures** (2 JoinModeTest + 36 KvCodec + 1 KvStateMachineLifecycle +
  1 Smoke + 2 StaleReadsSeedBug), BUILD SUCCESS — matches the report.
- Precedent verified in the 3.2.2 test sources:
  `GroupManagementBaseTest` starts servers "with null group" (lines 89,
  183) **and restarts them with null group** (line 207);
  `MiniRaftCluster.addNewPeers` (line 441) uses
  `RaftGroup.valueOf(groupId, emptyList)` — the empty-peers shape.
  `RaftServerProxy.initGroups` (257–269) recovers any group directory
  found by the storage scan, null group adds nothing — so `--join` as
  the restart mode for joined nodes is correct at source level.
- The join-race rationale for the empty-peers group is real:
  `LeaderElection` returns NOT_IN_CONF for a division not in its own
  conf (line 420 — an empty conf cannot elect), and
  `shouldSendShutdown` (RaftServerImpl 1417–1424) would shoot a
  populated-group joiner's premature candidacy.
- Live: pool nodes log `ratis-kv join mode: id=… formed no group; …`
  followed by the **byte-identical contract startup line** with
  `peers=` as the 7-node address book; n6 in my membership #1 booted
  join-mode twice (initial pool + post-replace pool return).
- **SUT diff minimality**: main-source diff is exactly the two itemized
  pieces — `--join` (ServerOptions flag + Main's conditional
  `setGroup` + usage text, ~40 lines) and the lifecycle fix
  (`KvStateMachine` only, ~30 lines). No drive-bys; test-scope changes
  (MiniCluster variant + two new test classes) are appropriate;
  Apache-2.0 headers present on both new files. The single
  `.setConfiguration` call site in the whole tree goes through the
  `Arguments` builder (RATIS-2640 avoided); no use of the broken
  overload anywhere.

### Emphasis 5 — nemesis orchestration

- COMPARE_AND_SET against the census: verified in
  `client/set-configuration-cas!` + `nemesis/attempt-set-conf!`
  (census servers/listeners as current-conf arguments).
- Legal-outcome tolerance: every conf call is wrapped; `SetConfigurationException`
  → one re-census + retry; other reconfiguration/transfer-window
  rejections land as `[:error class msg]` tokens in the op value. Live:
  membership #2's replace-done executed its remove-retry successfully
  (`:remove-retry {:success? true …}`) after the first half's reply
  exhausted — the mismatch/retry machinery exercised on a real cluster.
- Replace-dead sequence matches the brief exactly: kill voter →
  CAS-remove → (fault window) → ensure-removed → bootstrap+commit pool
  node → wipe-storage+restart victim as `--join` pool (log preserved as
  evidence).
- **Voter-floor probe** (review brief: "can a hostile schedule drive it
  below 3?"): by schedule alone, no — every move's draw-time check is
  sound when belief matches reality, and my two membership runs'
  adopted confs ranged 3–6 servers (floor touched, never breached).
  There is, however, a *race-gated* theoretical breach: the floor is
  checked against the first census (remove) or belief (replace-dead),
  while the commit applies the transform to a **fresh** census on the
  mismatch-retry path (`set-conf!`) and in `replace-done`'s
  remove-retry — neither re-checks the floor. Reaching a 2-voter conf
  needs a lost-reply remove (belief high) *plus* a full census failure
  at the next draw *plus* census recovery on the retry — a double
  failure race, unobserved across all runs, consequences bounded to
  band policy (a 2-voter raft still serves and the next add block
  recovers). Finding 1, non-blocking, with a one-line hardening
  suggestion.

### Emphasis 6 — the listener-staging probe

Reproduced once, identical outcome to the worker's two runs, so the
wedge is now **3/3 across two environments**:

- Conf mechanics all pass: stage n7 as listener (commits), replication
  to n7 confirmed from its own log (conf-line census 3 → 5 across the
  probe), **listener→voter promotion commits**, demote commits, remove
  commits, pool restored. Run exit 0.
- The wedge: both targeted linearizable reads at n7 — one as listener,
  one ~15 s *after promotion to voter* — fail
  `ServerNotReadyException: n7@… is not in [RUNNING]: current state is
  STARTING`.
- Mechanism verified at source, every link: `checkStaging` marks
  catch-up only through `containsInConf(f.getId())`
  (LeaderStateImpl 845–847); `PeerConfiguration.contains(id)` defaults
  to the FOLLOWER map (122–124) so a staged listener is never marked;
  the append's `initializing` flag is `!isCaughtUp(follower)` (line
  614); `RaftServerImpl` line 1611 transitions STARTING→RUNNING only on
  a non-initializing append. The one-line upstream suspect
  (`containsInConf(id, FOLLOWER, LISTENER)` in the marking filter) is
  the right framing. Evidence grade: strong — reproducible wedge with a
  pinned mechanism, corroborating RATIS-1825's open doubt at the
  client-availability level while refuting it at the conf level.

### Emphasis 7 — the matrix

All on the fixed SUT, my environment (`env/run.sh test --nemesis <kind>`;
membership at `--time-limit 120` ×2 = the review-brief probe's second
option, rest at the worker's parameters):

| Run | Exit | ok / fail / info | Conf transitions | Analysis | Store (`20260806T…`) |
|---|---|---|---|---|---|
| membership #1 (120 s) | 0 | 1093 / 407 / 0 | **8** | 0.32 s | `…membership/062203.182Z` |
| membership #2 (120 s) | 0 | 1081 / 409 / 10 | **8** | 0.42 s | `…membership/062507.140Z` |
| membership-snapshot-churn (300 s) | 0 | 1526 / 588 / 0 | 8 | 0.37 s | `…membership-snapshot-churn/062731.416Z` |
| listener-probe (180 s) | 0 | 1083 / 417 / 0 | 4 | 0.29 s | `…listener-probe/063252.851Z` |
| mixed-all (300 s) | 0 | 1082 / 417 / 1 | 5 (reported, not required) | 0.40 s | `…mixed-all/063409.066Z` |
| membership + seed-bug (300 s) | **1** | 1085 / 415 / 0 | 21 | 0.46 s | `…membership-seedbug-stale-reads/063939.238Z` |

- Membership ×2: 8 transitions each — and the arithmetic is exact
  (2 adds + 2 removes + 2 replaces × 2 transitions = 8), zero skips,
  block coverage held both runs. Floor 3 never breached (adopted confs
  3–6 servers).
- Combined: `:membership-evidence` 8 transitions AND
  `:joiner-install-evidence {:valid? true, :joined [n7 n2 n5 n6],
  :joined-with-installs [n2 n5 n6]}` — n7 joined pre-first-snapshot
  (correctly needed no install), and the three post-snapshot joiners
  all installed during staging; **n5 twice, surviving both**. Both
  evidence kinds in one green run, as criterion 3 demands.
- mixed-all: green; drew crash/partition/transfer/churn/membership
  segments (this draw happened to skip pause — the six-kind pool is
  pinned by the generator unit test; a ~12-segment run misses one kind
  ~11% of the time, which is the uniform draw working as designed).
- Seeded red under membership: **convicts all five keys**
  (`:failures [0 1 2 3 4]`) while 21 conf transitions churn around it;
  membership evidence stays green — linearizability convicts, exactly
  as designed.
- `:info` sanity: 11 total across six runs; membership #2's 10 all sit
  inside its replace-dead window — whose victim was the sitting
  *leader* (n1), so the exhausted retries during the forced election
  are the expected shape; calm phases are clean everywhere.
- Harness suite: **77 tests / 823 assertions, 0 failures** (matches
  report). Note for future reviewers: `clojure -M:test` needs the SUT
  jar `install`ed (documented in deps.edn) *and* a real `git` for the
  cognitect test-runner git dep — the env image deliberately ships
  none; I ran it in a one-off container with both provided.

### Ownership / headers / artifacts / workflow

Diff touches exactly the granted files; the workflow change is the
single scenarios-default line; `docs/RUNS.md` is append-only; both new
Java files carry Apache-2.0 headers; no build/run artifacts committed.
The proposed DESIGN §2.6 row matches the live-observed startup lines
byte-for-byte.

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | non-blocking | `harness/src/ratis_jepsen/nemesis.clj:550` (`set-conf!`), `:660` (`member-replace-dead!`), replace-done's remove-retry | Voter-floor enforcement is draw-time only: the CAS-mismatch retry and the replace-done remove-retry re-apply the conf transform to a fresh census without re-checking the floor, and replace-dead checks belief rather than census. A sub-floor (2-voter) conf is reachable only via a lost-reply belief drift *plus* a census outage at the next draw *plus* recovery on the retry — unobserved in all runs (my confs stayed 3–6); consequences are band-policy, not correctness. Hardening: skip the commit when the transformed server set is smaller than `min-voters`. |
| 2 | non-blocking | `harness/src/ratis_jepsen/checker.clj:conf-transition-indexes` | The evidence counts *adopted* transitional entries (logged at append), not strictly *committed* ones — an appended-then-truncated (old,new) entry from a dying leader could in principle count. The law's intent ("the run exercised reconfiguration") is still served, and every gate's count was corroborated by client-acked moves in history; noting for precision. |
| 3 | non-blocking (observation) | `jobs/08-membership-churn/08_report.md` | The RECOVER-masking half of the blast-radius story has a nuance worth recording when BACKLOG 7 is rewritten: the churn heal's start-everything is pidfile-based, so a live process with a dead division is *not* restarted by the heal — the corpse persists until the same node is drawn as a kill victim again (or run end). Masking was therefore probabilistic, which makes Job 07's consistent greens even more clearly a blind spot of the checkers, not luck. |
| 4 | non-blocking | `harness/src/ratis_jepsen/nemesis.clj:listener-demote!` | Demote/remove have no floor guard of their own; safe today because the probe script only demotes the node it just promoted, but any future randomized listener nemesis would inherit an unguarded server-list shrink. Same one-line guard as finding 1 covers it. |

## Suggestions (non-blocking)

- Apply the `min-voters` guard inside the three remove-shaped
  transforms (findings 1/4); it also future-proofs the L3 orchestrator
  reuse this nemesis rehearses.
- When the coordinator rewrites BACKLOG 7 per emphasis 2, fold Review
  07's `state is CLOSED` trace text into the upstream report as the
  historical corroboration — it was the division-suicide's fingerprint
  ten hours before Job 08 named the mechanism.
- The worker's own suggestions list is good; the log-derived
  joined-nodes variant (grounding "joined" in the same transitional
  entries the count uses) is the one I'd prioritize, since it removes
  the only false-red path in the new evidence law.
- Consider a `membership`-kind CI note mirroring the churn-rate note:
  at CI's default `--time-limit 300` the dedicated kinds are fine
  (evidence floor 2 vs ~20 observed), so no action needed there — only
  the Job 07-inherited `snapshot-churn` rate caveat stands (already in
  the worker's report).

## Verification notes

- Worker branch consumed read-only via `git worktree add
  ../job-08-under-review FETCH_HEAD`; nothing pushed to it.
- My environment needed the same uncommitted proxy accommodations the
  worker documented (local ubuntu:24.04 shim baking the session proxy
  CA + https apt sources; `RJ_EXTRA_CA_BUNDLE`; `--network=host` build
  args; Maven volume seeded via one-off containers). None of this
  touches the repo.
- The broken-SUT reconciliation run used main's `KvStateMachine`
  (pre-fix) with main's harness — the exact Job 07 configuration — in
  the same topology.
