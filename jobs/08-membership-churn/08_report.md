# Job 08 report — M2 part 2: membership churn (the L3-orchestrator rehearsal)

## Summary

The harness now drives dynamic membership against the dormant pool and
*proves* it: `--nemesis membership` cycles add (pool → voter),
remove (voter → pool) and replace-dead (the L3 orchestrator's
kill → remove-from-conf → add-pool-node → wipe-and-restart-as-pool
sequence) over the 7-node topology, and a new conf-change evidence
checker fails any dedicated membership run whose node logs carry fewer
than N committed configuration changes. The SUT grew the anticipated
`--join` launcher mode: start a `RaftServer` with **no group** — the
`GroupManagementBaseTest` "null group" precedent — and the harness
bootstraps it via `GroupManagementApi.add` with the *empty-peers*
group before `setConfiguration` commits it into the conf; the same
flag is also the correct *restart* mode for any dynamically-joined
node, because the 3.2.2 proxy recovers stored groups by scanning the
storage dir.

**The central event of this job is a conviction, caught by the
evidence-assertion law on its first combined run** (details in "The
find"): at ratis-3.2.2, a state machine built on `BaseStateMachine`
without a custom `pause()` gets its **division killed by every live
streamed snapshot install** — `BaseStateMachine.pause()` is an empty
method, the install path pauses the SM and
`StateMachineUpdater.reload()` hard-asserts the PAUSED lifecycle state,
and the assert failure's catch-all closes the division. A staged
joiner self-destructed 25 ms after logging `successfully install the
entire snapshot`, the leader's `setConfiguration` then wedged ~60 s in
staging NOPROGRESS while refusing every other conf change and
leadership transfer, and the combined gate's `:no-joiner-install-evidence`
verdict is what dragged the whole chain into the light. The SUT fix
(lifecycle discipline exactly as upstream's own test state machine
does it) is itemized below; the conviction stores are preserved; this
also retroactively explains Job 07's "ServerNotReadyException
install-retry storm" (BACKLOG item 7) — the leader was hammering
installs into a division the *previous* install had just killed.

Decisions to look hardest at: (1) all `setConfiguration` calls go
through the `Arguments` builder in `COMPARE_AND_SET` mode against a
**log-line conf census** — the API alternative
(`GroupInfoReply.getConf()`) is **dropped by the 3.2.2 wire
serializer** (`toGroupInfoReplyProto` never sets the field; found
during this job, upstream-report candidate), and only transitional
`old=peers:` conf lines count as evidence because **every new leader
re-appends the current conf as a stable entry** (elections would
otherwise masquerade as conf changes); (2) after the first live
shakedown drew 15 uniform move segments with **zero adds** (P≈0.2%,
conf ratcheted to the floor), dedicated membership generators now draw
moves in shuffled blocks of three — coverage of all three moves per
run is guaranteed by construction, order stays random, and each block
nets a voter change of zero.

## What was built

| File | One line |
|---|---|
| `sut/ratis-kv/src/main/java/ratis/jepsen/kv/ServerOptions.java` | `--join` flag (parse + record field + docs); id-in-peers rule unchanged (the self entry supplies the bind port) |
| `sut/ratis-kv/src/main/java/ratis/jepsen/kv/Main.java` | join mode skips `setGroup` (builder group stays null); a `ratis-kv join mode:` line before the **unchanged** contract startup line; usage text |
| `sut/ratis-kv/src/test/java/ratis/jepsen/kv/MiniCluster.java` | voters+joiners start variant, all-nodes client, per-node options accessor (test scope) |
| `sut/ratis-kv/src/test/java/ratis/jepsen/kv/JoinModeTest.java` | new: `--join` parse contract + live in-JVM join flow — group-less start, `add` with empty group, CAS `setConfiguration` in, serve a targeted linearizable read, CAS back out |
| `harness/src/ratis_jepsen/client.clj` | membership admin interop: `member-peer`, `group-add!` (empty-peers group), `set-configuration-cas!` (Arguments builder — RATIS-2640 array overload avoided), `group-members`, `targeted-read!`; worker `open!` takes its group-spec from the intended voters in membership runs (a late-spawned worker must not aim at pooled ex-voters) |
| `harness/src/ratis_jepsen/db.clj` | `join-server-args` + posture-aware start (`dynamic-node?` via the shared membership state), storage-only wipe (log survives as evidence), conf census: `parse-conf-line` + `conf-census!` (highest `set configuration` index across nodes), `conf-line-count!` |
| `harness/src/ratis_jepsen/nemesis.clj` | membership state + 5±2 band constants; conf-aware fault targeting (`conf-nodes` — crash/pause/churn/transfer target conf members, not dormant pool nodes); the membership nemesis (`:member-add`, `:member-remove`, `:member-replace-dead`/`done` as the L3 fault pair) and the scripted listener probe; shuffled-block move draw; combined churn-first alternating generator; kinds += `membership` \| `membership-snapshot-churn` \| `listener-probe`; mixed-all draws membership as a sixth kind |
| `harness/src/ratis_jepsen/checker.clj` | membership evidence (distinct transitional conf-entry indexes; `:no-conf-change-evidence`) and joiner-install evidence (`:no-committed-join` / `:no-joiner-install-evidence`) checkers |
| `harness/src/ratis_jepsen/outcome.clj` | new row: `ServerNotReadyException` ⇒ write `:info` / read `:fail`, quiet (STARTING at boot or CLOSED after a removal's self-shutdown); GroupMismatch loud-message wording covers the benign membership race |
| `harness/src/ratis_jepsen/workload/register.clj` | evidence checkers composed; membership evidence required for the two dedicated kinds, joiner-install for the combined kind only |
| `harness/src/ratis_jepsen/core.clj` | new kinds + cycle/evidence knobs; 7-node `:nodes` override + shared membership-state atom for membership-bearing kinds; per-kind workload defaults (combined kind inherits Job 07's churn numbers so CI needs no extra flags) |
| `harness/test/…` | nemesis vocabulary/segments/blocks/probe, conf-line parsing, join args, posture selection, evidence extraction/verdicts, `ServerNotReadyException` row |
| `.github/workflows/jepsen.yml` | the granted single line: scenarios default += `membership,membership-snapshot-churn` |
| `docs/RUNS.md` | M2 part-2 ledger entries |
| `jobs/08-membership-churn/08_report.md` | this report |

## The SUT diff (brief: "keep it minimal and separately described")

Main-source diff is **three files**, in two independent pieces:

**Piece 1 — the anticipated `--join` mode (~40 lines):**

1. `ServerOptions`: one new flag (`--join`) parsed into one new record
   component; nothing else touched. `--id` must still appear in
   `--peers` — in join mode the peers list is an address book and the
   self entry supplies the bind port.
2. `Main.buildServer`: when `join`, the builder's group is simply never
   set (it defaults to null — first-class at 3.2.2). Everything else —
   production properties, `RECOVER`, state machine, ports — is
   identical. `main` additionally logs one `ratis-kv join mode:` line.

**Piece 2 — the lifecycle fix this job's conviction forced (~30
lines, `KvStateMachine` only):** `initialize` wraps its existing body
in `LifeCycle.startAndTransition` (SM ends RUNNING), `pause()`
transitions PAUSING→PAUSED, `reinitialize()` resumes
PAUSED→STARTING→RUNNING after its existing snapshot load. No behavior
change on any path except the one that previously killed the division
(see "The find"); without it, acceptance criterion 3 (combined run
with install evidence on a joining node) is unsatisfiable at 3.2.2 —
the joiner dies on the very install the criterion demands.

Verified semantics (ratis-3.2.2 source, confirmed live):
group-less start + empty storage ⇒ the proxy hosts nothing and awaits
`GroupManagementApi.add` (`GroupManagementBaseTest.testGroupWithPriority`
starts servers exactly this way); group-less start + existing storage ⇒
`RaftServerProxy.initGroups` scans the storage dir and RECOVERs any
group directory found — so `--join` is also the correct restart mode
for a node that joined dynamically, and the harness uses it for every
node that has ever been through the pool.

**Proposed DESIGN §2.6 edit** (coordinator applies on merge): add one
row under the Startup line row —

> | Join mode (M2) | `bin/ratis-kv --id <id> --peers <full 7-node address book> --storage /var/lib/ratis-kv --join` starts a server that forms **no group**: on fresh storage it hosts nothing until bootstrapped (`GroupManagementApi.add`, then committed by `setConfiguration`); existing storage is recovered instead, making `--join` the restart mode for dynamically-joined nodes. The contract startup line is emitted **unchanged** (in join mode `peers=` is the launch address book, not a formed conf), preceded by `ratis-kv join mode: id=<id> formed no group; awaiting GroupManagementApi.add (existing storage is recovered instead)`. |

## The join flow, as shipped (verified in source, exercised live)

1. **Bootstrap**: `client.getGroupManagementApi(target).add(RaftGroup.valueOf(GROUP_ID))`
   — the **empty-peers** group. The created division has an empty conf,
   so it *cannot* start elections (`LeaderElection`'s `NOT_IN_CONF`
   guard); this is `MiniRaftCluster.addNewPeers`' exact shape, reached
   via the RPC the brief names. Passing a populated group instead would
   arm a race: the joiner's first election timeout fires before the
   conf commits, and the leader answers an unknown candidate's vote
   request with `shouldShutdown` — the join would kill itself.
   `AlreadyExistsException` (an earlier attempt bootstrapped the
   division) is treated as success.
2. **Commit**: `AdminApi.setConfiguration` with
   `SetConfigurationRequest.Arguments` — builder only; the
   `(RaftPeer[], RaftPeer[])` overload is confirmed broken in 3.2.2
   source (it writes the servers into the listeners slot — RATIS-2640,
   our upstream find). `Mode.COMPARE_AND_SET` against the censused
   current conf; `SetConfigurationException` (CAS mismatch) triggers
   one re-census + retry; `ReconfigurationInProgressException`,
   `ReconfigurationTimeoutException` (staging NOPROGRESS) and
   `LeaderSteppingDownException` (transfer-window rejection) are
   recorded as legal outcomes.
3. **Staging**: the leader catches the new peer up *before* appending
   the (old,new) conf entry (`LeaderStateImpl.startSetConfiguration`
   bootstrap staging), and a staged follower is sent a real
   install-snapshot **whenever the leader holds any snapshot**
   (`LogAppender.shouldInstallSnapshot` rule 3) — this is why the
   combined kind alternates churn-first: the opening churn cycle forces
   a snapshot, and every subsequent join must cross the
   bootstrap-catch-up-via-install-snapshot path.

## The conf census and the evidence rule (read this before the checker)

Two 3.2.2 realities shaped this:

1. **`GroupInfoReply.getConf()` is unusable over the wire.** The server
   populates the reply's conf field, but the wire serializer
   (`ClientProtoUtils.toGroupInfoReplyProto`) never sets it, so a
   remote client always sees it empty. Found while the join smoke test
   sat polling an Optional that could never fill; upstream-report
   candidate (fixed serializers exist on master). The census therefore
   reads what every replica *logs* each time it adopts a conf
   (`ServerState.setRaftConf`):

   ```
   n1@group-ABBC16E54704: set configuration conf: {index: 5,
     cur=peers:[n1|…, n2|…]|listeners:[], old=null}
   ```

   `db/conf-census!` takes each node's last such line and keeps the
   highest index — belief only, backing the CAS arguments; staleness
   costs a recorded mismatch-retry, never correctness.
2. **Only transitional conf entries may count as evidence.** Every new
   leader re-appends the *current* conf as a stable entry at its
   startup index (`LeaderStateImpl.StartupLogEntry`) — so counting all
   `set configuration` lines would count **elections**. A real
   reconfiguration is the only producer of a transitional entry
   (`old=peers:…`); the committed stable follow-up, boot-time replays
   and leader startups all log `old=null`. The membership evidence is
   therefore the count of **distinct transitional indexes** across all
   node logs (deduplicated across replicas and restarts), with
   `--membership-min-conf-changes` (default 2) as the floor and
   `:no-conf-change-evidence` as the distinct failure.

## The find: live installs kill the receiving division at 3.2.2 (read this second)

The brief's Note said membership + snapshot churn is where the real
bugs cluster; the first combined run proved it, and the
evidence-assertion law is what caught it.

**The observable**: the first `membership-snapshot-churn` gate exited 1
with `:joiner-install-evidence {:valid? false, :error
:no-joiner-install-evidence}` — three nodes had joined during the run
and none showed an install-snapshot receive. Linearizability, liveness
and conf-change evidence were all green; the run failed *only* because
the path it exists to prove never completed. Store preserved:
`…membership-snapshot-churn/20260805T214003.673Z`.

**The chain, from that store's logs** (all line references verbatim in
the store; timestamps 21:44:09–21:45:12):

1. Leader n6 stages joiner n4 (`startSetConfiguration` cid=1672,
   21:44:09.762) and — per `shouldInstallSnapshot` rule 3 for
   bootstrapping followers — streams it a snapshot immediately.
2. n4 installs it cleanly: `SnapshotManager - Installed snapshot`,
   `snapshotIndex: updateIncreasingly -1 -> 1729`, `successfully
   install the entire snapshot-1729` (21:44:09.834).
3. **25 ms earlier, n4's updater thread had already died**:
   `StateMachineUpdater caught a Throwable —
   java.lang.IllegalStateException at
   Preconditions.assertTrue(StateMachineUpdater.reload:230)`, and the
   updater's catch-all ran `server.close()` — the division logs
   `shutdown` and every subsequent leader append fails
   `ServerNotReadyException: … current state is CLOSING`.
4. The leader keeps appending into the corpse (`Decrease nextIndex to
   1730` bounce at 21:45:12 — 63 s later), staging can never observe
   catch-up, every other `setConfiguration` in the window is refused
   `ReconfigurationInProgressException` and a leadership transfer is
   refused `when raft reconfiguration in progress`, until the staging
   dies `ReconfigurationTimeoutException … due to NOPROGRESS`.

**Root cause, pinned in ratis-3.2.2 source**: `reload()` asserts
`stateMachine.getLifeCycleState() == PAUSED`
(`StateMachineUpdater.java:230`); the install path calls `sm.pause()`
first (`ServerState.installSnapshot:476`) — but
`BaseStateMachine.pause()` is an **empty method** that never touches
the lifecycle, and PAUSING's only legal predecessor is RUNNING, which
`BaseStateMachine.initialize` also never enters. Upstream's own test
state machine (`SimpleStateMachine4Testing`) overrides `pause()` with
the PAUSING→PAUSED transitions and resumes in `reinitialize()` — which
is exactly why upstream's install tests pass while every naive
`BaseStateMachine` user's division dies on first live install. The
same crash is on the second install receiver in the same store (n5,
21:42:54) — twice in one 300 s run.

**Blast radius before the fix**: bounded but real — the process
survives (only the division closes), a later `kill -9` + restart
resurrects the division from disk, and the 4-of-5 majority keeps
serving, which is why Job 07's snapshot-churn runs stayed green while
(in hindsight) every live install was killing its receiver. It also
re-frames Job 07's ~400/15.6 s `ServerNotReadyException` install-retry
storm (BACKLOG item 7): the leader was retrying installs into a
division the previous install had just closed — the no-backoff loop
and the division-suicide are two faces of the same event.

**The fix** (SUT, itemized in the diff section): `KvStateMachine` now
does the lifecycle bookkeeping upstream's test SM does — RUNNING after
`initialize` (via `LifeCycle.startAndTransition`), PAUSING→PAUSED in
`pause()`, PAUSED→STARTING→RUNNING after the post-install
`reinitialize()` — and `KvStateMachineLifecycleTest` pins the exact
pause→reinitialize contract `reload()` enforces, through a real
server. The re-run gates below all executed on the fixed SUT.

**Upstream-report candidate** (with RATIS-2542's own wishlist naming
`notifyInstallSnapshot` correctness): either `BaseStateMachine` should
implement the lifecycle contract its own updater enforces, or
`reload()` should fail the install (not the division) on a non-PAUSED
SM. Repro recipe: any `BaseStateMachine`-derived SM without a `pause()`
override + any leader-streamed install; 100% reproduction observed
(2/2 receivers in one run).

## Liveness gating assessment (brief deliverable 5)

Narrow by design, and the runs bear it out: `:member-add` and
`:member-remove` are **actions** (never gate liveness) — staged
catch-up of a joiner runs in the leader's background and a removed
voter leaves behind a smaller *full-capacity* conf, so neither excuses
a stalled majority; the gate runs' liveness checkers stayed valid with
adds/removes/promotes happening inside calm regions. Only
`:member-replace-dead` → `:member-replace-done` opens a fault window —
between those ops a voter is genuinely dead (and, if the CAS failed,
still in the conf), which is exactly the exposure the checker must not
blame the SUT for. This is the narrowest gating consistent with the
sequence.

## How it was verified

Versions: ratis 3.2.2, jepsen 0.3.13, SUT `ratis-kv 0.1.0-SNAPSHOT`,
JDK 21. Commands from the repo root.

TBD-RUN-TABLE

## Deviations from the brief

1. **The conf census is log-line-based, not `GroupInfo`-based.** The
   brief suggested "server logs and/or GroupInfo queries"; GroupInfo's
   conf field turned out to be dropped by the 3.2.2 wire serializer
   (see above), so logs are the only role-split conf source that
   exists. `group-members` (GroupInfo's group view, which *is*
   serialized) remains in the client for API-level cross-checks.
2. **`GroupManagementApi.add` is called with the empty-peers group**,
   not a populated one. The brief's flow ("add(group) before the leader
   commits it into the conf") is preserved; the empty conf is the
   upstream-precedented shape that cannot race an election (details in
   "The join flow").
3. **Remove returns the node to the pool via kill + storage-wipe +
   `--join` restart** as part of the same move. The brief specifies
   wipe-and-restart only for replace-dead; a removed-but-stale voter
   cannot be re-added later (its recovered division would collide with
   `groupAdd`), so the pool invariant requires the same decommission
   for plain removes. The stale-conf window before the kill (a few
   seconds of a removed node still running with its old conf) is
   deliberately left in — organic RATIS-2274 exposure.
4. **The dedicated membership generators draw moves in shuffled blocks
   of three** rather than uniformly at random. The first live shakedown
   drew 15 uniform segments with zero adds (P≈0.2%) and ratcheted the
   conf to the floor; the gates and the combined kind's evidence need
   committed adds, so per-run coverage of all three moves is now
   guaranteed by construction (order still random; `mixed-all` keeps
   the single uniform membership move). The zero-add shakedown is
   preserved and was itself green — 10 committed transitions via
   removes/replaces (store `…membership/20260805T211831.264Z`).
5. **The combined kind carries per-kind workload defaults** (rate 1.4,
   ops-per-key 800 — Job 07's churn numbers) instead of the global
   defaults, because CI invokes scenarios with only `--nemesis` and
   `--time-limit` and the combined evidence needs the sustained write
   stream. Explicit `--rate`/`--ops-per-key` still win. (Job 07's
   `snapshot-churn` kind keeps global defaults per its report; see
   Suggestions for the CI implication.)
6. **`listener-probe` is not in the CI scenarios default.** The brief
   grants the scenarios-line update; membership and the combined kind
   are added. The probe's outcome is *signal* (pass or wedge — the
   brief allows report-and-defer), so wiring it as a CI gate would turn
   an open upstream question into a red build; it stays
   dispatchable by name.

## Known gaps and risks

- **The conf census parses an INFO log line** (`set configuration
  conf: {index: …}`). A Ratis version that rewords it breaks the
  census and the evidence checker together; both carry the observed
  phrasing and the version pin in docstrings, and the M5 version
  matrix should re-pin per version.
- **Intended-state drift**: the nemesis's `:voters` belief reconciles
  on every successful CAS, but a setConfiguration that *succeeds
  server-side* while the reply is lost leaves belief behind until the
  next census. Consequences are bounded: a mismatch-retry or a skipped
  move, never a correctness claim.
- **Evidence over-counting across pool round-trips**: a node's log
  survives its storage wipe (deliberately — it is run evidence), so a
  node that installs a snapshot, is removed, and joins again could
  satisfy joiner-install evidence with a *pre-removal* receive line.
  The gate runs' joiners were virgin-log nodes (n6/n7 first joins), so
  the quoted evidence is unambiguous; a stricter last-boot-segment
  filter is a suggestion below.
- **`ServerNotReadyException` write-path `:info`** is sound (an
  earlier same-callId attempt may have applied) but adds one more
  ambiguous row; counts stayed low in the gates (`:info` totals in the
  run table).
- **Worker `open!` reads the intended-voter belief** for its group
  spec; a worker opened during a belief-stale window may still target
  a pooled node and take one loud GroupMismatch `:fail` before its
  peer list self-heals via `NotLeaderException.refreshPeers` (3.2.2
  client). Observed zero times in the gate runs.

## Suggestions (out of scope)

- **Upstream-report candidates**, in likely-worth order: (1)
  `toGroupInfoReplyProto` drops the conf field at 3.2.2 (this job's
  find; trivially patchable, fixed on master); (2) the Job 07
  InstallSnapshot no-backoff storm (BACKLOG item 7) — this job's
  combined runs give it a second reproduction surface; (3) RATIS-2640
  is already ours.
- **CI note**: `snapshot-churn` under CI's default flags (rate 10)
  exhausts its op budget in ~25 s and may reach zero purge milestones
  — its required install-snapshot evidence can then fail an otherwise
  healthy CI run (Job 07 gates always passed `--rate 1.4
  --ops-per-key 800` by hand). Either give it per-kind defaults like
  the combined kind, or note the flags in the workflow input docs.
- **Joiner-install evidence could filter to the node's last boot
  segment** (split the snarfed log at the last `Jepsen starting` line)
  if pool round-trips ever make the existential check ambiguous.
- **Listener reads**: the probe's targeted-read signal (see the probe
  section) is worth a dedicated `--reads listener` mode in an M3+ job
  if upstream clarifies intended semantics.

## Environment notes (this execution sandbox, not the repo)

Same accommodations as Jobs 04–07, all uncommitted: local
`ubuntu:24.04` shim baking the session's TLS-re-terminating proxy CA +
https apt sources; `RJ_EXTRA_CA_BUNDLE=/root/.ccr/agent-proxy-ca.crt`
(the single proxy CA — the full session bundle exceeds run.sh's 64 KiB
build-arg cap) and `RJ_DOCKER_BUILD_ARGS="--network=host --build-arg
HTTPS_PROXY=…"` for the image build (buildkit's sandboxed build network
cannot reach the loopback proxy); control's `/root/.m2` seeded from the
host cache; gnuplot apt-installed into control post-up.
