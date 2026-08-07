# Job 11 report — M4: durability faults via lazyfs

## Summary

The harness now tests the failure class its own evaluation flagged as
untested everywhere: storage durability under power loss. `--durability`
puts every voter's raft storage on a per-node lazyfs mount (baked into
the env image at the Job 10 pin, inert otherwise), proven from the node
under a hard evidence law; three new nemeses drive it — `unsync-drop`
(minority power loss, **green**), `unsync-drop-all` (whole-cluster
power loss, **green**, liveness-gated), `torn-write` (a mid-write tear
on one follower's log — the node **refused to start loudly** under the
default `CorruptionPolicy=EXCEPTION` while the majority stayed
linearizable, one of the two legal outcomes). The metadata probe
answers the brief's double-vote question: **term/votedFor are fsynced
before the node acts on a vote** — from source and by a 609-sample
mount-vs-backing experiment — with one out-of-model caveat (no parent
directory fsync after the raft-meta rename) recorded below. The two
decisions a reviewer should look hardest at: the torn-write fault is
armed at runtime over the fifo with *next-write* semantics because the
pinned lazyfs silently drops fifo torn-ops carrying `occurrence=` (two
lazyfs bugs, verified live, documented below), and `unsync-drop-all`
carries pinned sizing (calm 70 s / 5 s window / 10 keys × 1 worker /
rate 0.5) because whole-cluster outages mass-produce forever-concurrent
`:info` ops and two earlier shapes OOMed knossos (both stores
preserved).

## What was built

| File | One line |
|---|---|
| `env/Dockerfile` | multi-stage lazyfs build at pin `045a0b3a…` (amd64-only stage per PLAN Q8; spdlog pre-cloned for libpcache's FetchContent — the spike's proxy accommodation), binary + `libpcache.so` at `/opt/lazyfs`, runtime adds `libfuse3-3`/`fuse3` + `user_allow_other`; inert unless a run mounts it |
| `env/README.md` | lazyfs section (pin, accommodation, inertness, `/dev/fuse`) + arch-support note |
| `harness/src/ratis_jepsen/env_contract.clj` | M4 additions: `lazyfs-bin`, `backing-dir`, `lazyfs-log-file` (shared by env and harness) |
| `harness/src/ratis_jepsen/db.clj` | mount lifecycle: per-node toml (128 MiB cache, documented sizing), daemonized lazyfs outliving SUT restarts, **the evidence law** (`prove-mount!`: mount-table type + fault fifo + fsync'd canary in the backing dir; any shortfall throws the distinct `DURABILITY MOUNT UNPROVEN` error), teardown/wipe including the mount-aware membership `wipe-storage!`, fault surface (`clear-cache!`, `torn-write!` + pure `torn-write-command`, `current-open-segment!`, `remount-lazyfs!`), lazyfs log collected per node |
| `harness/src/ratis_jepsen/nemesis.clj` | the three durability kinds + `durability-kinds`, fault→heal entries (liveness gating inherited), `durability-nemesis` (kill→drop ordering B; torn arm/fire/remount/restart with recorded `:started`/`:refused-start`/`:wedged`, plus armed-vs-now stale-path forensics on a never-fired tear), pinned cycles with the knossos-budget rationale, membership `pool-return!` made mount-aware |
| `harness/src/ratis_jepsen/checker.clj` | `durability-evidence` checker: clear-cache acks / fired tears counted from the snarfed lazyfs logs; dedicated runs failing with `:no-durability-fault-evidence` or `:torn-recovery-unproven` when the fault or its recovery half never really ran (a no-fire red quotes any recorded stale-armed-path forensics) |
| `harness/src/ratis_jepsen/core.clj` | `--durability` (forced on by durability kinds), `--torn-persist-part`, per-kind workload defaults for `unsync-drop-all` (rate 0.5, key-count 10), run-name suffix |
| `harness/src/ratis_jepsen/workload/{register,counter}.clj` | `:durability-evidence` composed (required for the dedicated durability kinds only) |
| `harness/scripts/metadata-probe.sh` | the bounded deliverable-4 probe (elections + mount-vs-backing sampling + drop/restart term check) |
| `harness/test/…` | unit tests: contract pins, toml content, torn command grammar, segment/package shapes, mixed-all stays durability-free, evidence counting/verdicts incl. the stale-armed-path forensics note (suite: 107 tests / 958 assertions) |
| `.github/workflows/jepsen.yml` | scenarios input description only: durability tokens documented as dispatchable, kept out of the default sweep, runner cost stated (bare tokens already reach `--nemesis`; the harness forces the topology on) |
| `docs/RUNS.md` | the M4 gate ledger (append) |

## The expectations table (deliverable 5)

Model note (BACKLOG item 4): destroying *durable* state unequally
across nodes is **out-of-model** — it deletes committed data that may
exist nowhere else, and no scenario here does it. `clear-cache` can
only destroy data lazyfs was never asked to fsync, so acknowledged
(fsynced) state is untouchable by construction; the torn write destroys
the un-fsynced tail of one write on **one** node while the entry lives
on the majority. Every fault below is therefore inside the model Ratis
promises to survive.

| Scenario | In model? | A pass looks like | A finding would look like |
|---|---|---|---|
| `--durability`, no faults | in (topology only) | identical behavior to plain runs; 5× mount proven | any mount unproven ⇒ the distinct error, run aborts |
| `unsync-drop` (kill -9 minority, drop un-synced cache, restart) | in — single-node power loss, the crash-consistency contract | green: every acked write present after restart (Ratis fsyncs each append before acking — Job 10, source-proven by Review 10); ≥1 clear-cache ack or the run fails itself | a lost acked write (linearizability or counter conviction) = a Ratis fsync bug or a lazyfs fsync-passthrough bug — both reportable (Review 10 finding 3) |
| `unsync-drop-all` (the same on every voter at once) | in — cluster-wide power loss; this is where Raft's durability argument is load-bearing | green on safety: no lost acked write, no stale read; a temporary availability gap is legal and liveness-gated via fault→heal | any safety violation; or liveness violation *outside* the gated windows |
| `torn-write` (one follower's next log append torn mid-write; lazyfs dies under it; remount + restart) | in — single-node torn page, majority intact | the victim either recovers cleanly (truncated tail) or **refuses to start loudly** (`CorruptionPolicy=EXCEPTION`, the default); the majority keeps serving linearizably; the tear must have fired or the run fails itself | a victim that starts and serves **silently wrong** data; a lost acked write; a cluster outage from one torn node |
| `counter × unsync-drop` | in | exactly-once holds: every `:ok` add counted once, `:info` 0-or-1, with retries demonstrably happening | `:lost-update` / `:double-count` / duplicate observed totals |
| metadata probe | in for file data; the rename-durability edge is **out** of lazyfs's model (renames pass through) | no mount-vs-backing divergence of `raft-meta`; no term regression across drop+restart | persisted term regressing below a term the node acted in — a node able to vote twice in one term: the highest-severity class; preserve everything and triage |
| negative arm (mount sabotaged) | — (harness self-test) | the run **fails** with `DURABILITY MOUNT UNPROVEN`, exit ≠ 0 | a durability run silently proceeding on the plain filesystem |

Out-of-scope shapes, deliberately not built: dropping *synced* state on
any node (out-of-model, BACKLOG 4); durability kinds inside `mixed-all`
(the mount is an opt-in storage topology per the spike's
recommendation 1, and a torn victim may legally stay down — random
composition would walk the cluster below its majority and convict Ratis
for our schedule; additionally, elections from composed faults could
roll the armed segment between arming and fire — the evidence checker
would catch it loudly, but the run would be wasted).

## How it was verified

All commands from the repo root; full stats and stores in
`docs/RUNS.md` (2026-08-07 section). The sandbox accommodations
(uncommitted, Job 04 precedent) are listed under Environment notes.

**1. Suites green; existing scenarios unaffected with the feature off.**

```
$ cd harness && clojure -M:test
Ran 107 tests containing 951 assertions.
0 failures, 0 errors.
$ sut/ratis-kv/mvnw -f sut/ratis-kv/pom.xml -q test    # SUT untouched
[exit 0]
$ env/run.sh test --nemesis partition --time-limit 300   # durability OFF
Everything looks good! ヽ('ー`)ノ    # 1078/406/16, all checkers :valid? true
```

Inertness proven on the nodes after the regression run: no
`fuse.lazyfs` in `/proc/mounts`, no lazyfs process, no
`/var/lib/ratis-kv.root`, no `/var/log/lazyfs.log`; the store's
per-node dir carries only `ratis-kv.log`. One deliberate delta,
disclosed in Deviations: `results.edn` gains an informational
`:durability-evidence` map (all-zero, `:valid? true`) — the same
compose-unconditionally pattern Jobs 07/08 set with their evidence
checkers.

**2. Mount evidence per node; negative arm.**

```
$ env/run.sh test --durability --time-limit 30
… ratis-jepsen.db: n1 durability mount proven (lazyfs on /var/lib/ratis-kv over /var/lib/ratis-kv.root)
    [same line for n2..n5]
Everything looks good!
```

Negative arm — `/opt/lazyfs/lazyfs` on n3 replaced by a stub that exits
0 (so the daemon "starts" and no mount ever appears), then a plain
durability run:

```
$ env/run.sh test --durability --time-limit 60 ; echo $?
clojure.lang.ExceptionInfo: DURABILITY MOUNT UNPROVEN on n3 (:mount-await):
  no fuse.lazyfs mount on /var/lib/ratis-kv within 60000 ms; … — a durability
  run that is not actually on lazyfs is a broken test; refusing to continue
255
```

(store `…register-none-durability/20260807T105338.963Z`, jepsen.log;
binary restored afterwards and a healthy run re-verified green.)

**3. The three nemeses, each with its stated expectation.**

```
$ env/run.sh test --nemesis unsync-drop --time-limit 300
Everything looks good!    # 1059/441/0; 17 clear-cache acks across 10 kill+drop cycles
$ env/run.sh test --nemesis unsync-drop-all --time-limit 300
Everything looks good!    # 989/392/95; 4 whole-cluster losses (20 acks); liveness gated, analysis 8.2 s
$ env/run.sh test --nemesis torn-write --time-limit 300
Everything looks good!    # 1082/418/0; tear fired; victim refused loudly; majority linearizable
```

The torn run's chain, verbatim from the store: lazyfs
(`n3/lazyfs.log`) — `Write to path …/current/log_inprogress_0: will
persist 16 bytes from offset 51333` then `Killing LazyFS pid …!`;
restart (`n3/ratis-kv.log`) — `ChecksumException: Log entry corrupted:
Calculated checksum is 0A482F49 but read checksum is 00000000` →
`IllegalStateException: … Failed to initRaftLog`; history op —
`{:outcome :refused-start, :fired true, :victim "n3", :log-tail …}`.
The first torn attempt is preserved as the evidence law's live
demonstration: armed-but-never-fired ⇒ `Analysis invalid!` with
`:no-durability-fault-evidence` (`…torn-write/102955.099Z`; root cause
under Deviations).

**4. Metadata probe: source + experiment.**

Source at 3.2.2 (all files in `ratis-server`/`ratis-common` sources
from Maven Central): `RaftServerImpl.requestVote` calls
`state.persistMetadata()` inside the synchronized block **before** the
reply proto is built (`// sync metafile`), and the candidate's own
`ServerState.initElection(ELECTION)` persists after
`currentTerm.incrementAndGet()` before any request is sent;
`RaftStorageMetadataFileImpl.atomicWrite` writes `raft-meta.tmp`
through `AtomicFileOutputStream`, whose close path is
`FileUtils.newOutputStreamForceAtClose` → **`FileChannel.force(true)`**
→ `Files.move(tmp, raft-meta, REPLACE_EXISTING)` (rename(2)). So the
vote record is durable-at-file-level before the node acts. **No fsync
of the parent directory follows the rename** — see Known gaps.

```
$ env/run.sh test --durability --time-limit 30 --leave-db-running
$ harness/scripts/metadata-probe.sh 3
probe: leader=n4 victim=n1 … kill -9 leader n4 … new leader after election: n2
probe: samples=250 divergent(mount-term>backing-term)=0
probe: victim acted state [term=2 votedFor=n2 ] — killing + dropping un-synced cache
probe: survives power loss  [term=2 votedFor=n2 ]
probe: recovered after loss [term=2 votedFor=n2 ]
probe: no regression (acted=2 recovered=2)
    [cycles 2 and 3 identical in shape: terms 3 and 4; 170 and 189 samples, 0 divergent]
probe: VERDICT: PASS — raft-meta never diverged mount-vs-backing in any
probe: sample, and the recovered term never regressed …
```

Stated plainly: **at 3.2.2, within lazyfs's model (file-data
durability; renames pass through), the vote metadata cannot regress
across a power loss — Ratis does not have the class the external
campaign found in the other library.** The one edge lazyfs cannot test
is the un-fsynced rename (below).

**5. Counter under unsync-drop — exactly-once holds.**

```
$ env/run.sh test --workload counter --nemesis unsync-drop --time-limit 300
Everything looks good!    # 2091 ok / 3 fail / 27 info; retry evidence 201 retries / 78 ops;
                          # 15 clear-cache acks; per-key bounds + observed-total pinning all valid
```

**6. Costs and established reporting.** lazyfs image stage: **1 m 54 s**
per cold build, measured uncached at `-j4` (every CI runner pays it on
every job, durability or not — the coordinator decides whether that
changes defaults; the tokens stay out of the default sweep). Durability
DB setup: **6–8 s** vs **4.7 s** plain (five parallel 128 MiB cache
pre-allocations ≈ 2–3 s — sized deliberately; 5 × 1 GiB defaults would
have cost ~5 GiB RSS and ~8 s/mount, the spike's warning). Analysis
times: 0.5–8.2 s across the gates (the 8.2 s is drop-all, by design the
heaviest); `:info` sanity: zero in calm-phase, drop-all's 95 all inside
its four gated windows; ownership respected (no `sut/**` changes);
Apache-2.0 headers on all new files.

## Deviations from the brief

1. **Torn-write arming mechanism.** The brief left the mechanism open
   ("lazyfs's partial-write fault"); the Job 10 spike sketched static
   toml configuration. Shipped: runtime fifo arming with **next-write
   semantics**, because the pinned lazyfs has two stacked bugs verified
   live in this job: (a) the fifo parser for `torn-op` rejects the
   `occurrence=` attribute its own README documents (unknown-attribute
   branch ⇒ the whole fault is silently dropped) while (b) still
   logging `configured successfully` (it checks only
   `add_torn_op_fault`'s errors, which never ran) — and
   `add_torn_op_fault` hardcodes `occurrence=1` besides. Without
   `occurrence=` the arm works and tears the *next* write — more
   deterministic for a nemesis than an occurrence count. The toml route
   (which honors occurrence but binds at mount time) was verified
   working and remains available. Both bugs are upstream-able to
   lazyfs; the first torn store (`…102955.099Z`) is the misleading-
   success artifact.
2. **`unsync-drop-all` sizing is pinned, with two failed shapes
   preserved.** The brief implies a plain "same fault on every node"
   cycle; a naive one OOMs the linear checker — whole-cluster outages
   make every in-flight write an honest, forever-concurrent `:info`
   (one per thread per ~5 s of outage, rate-independent). Shipped shape:
   calm 70 s / 5 s window / key-count 10 (one worker per key) / rate
   0.5, all as kind defaults so bare CI tokens work. This is the
   sanctioned budget lever (DESIGN §6, Job 07 precedent), applied and
   documented rather than silently tuned.
3. **`mixed-all` does not draw durability faults.** "Composable into
   mixed-all only where sensible" — judged not sensible; reasons in the
   expectations table's out-of-scope note (opt-in storage topology; a
   refused torn victim legally stays down; restart- or election-driven
   segment rolls would stale the armed path — every restart rolls the
   open segment, not just term changes/8 MB, and mixed-all restarts
   nodes constantly). Unit-tested (`mixed-all stays durability-free`).
4. **Clean-recovery arm of torn-write not observed.** Both
   `persist-part` shapes bias toward refusal in practice: a mid-entry
   cut leaves a checksum-broken entry (observed), a hole leaves
   non-zero bytes past the terminator. The refusal arm is demonstrated;
   `--torn-persist-part` exists to explore the space; a tear landing
   exactly on an entry boundary (clean truncation) was not hunted —
   noted as a gap, not hidden.
5. **CI diff is description-only.** Bare tokens already flow to
   `--nemesis` and the harness forces the topology on, so the minimal
   itemized diff is the documented token list + cost note; the default
   sweep is unchanged (coordinator decides).
6. **`results.edn` shape delta when durability is off**: the
   informational `:durability-evidence` key (all-zero, valid) joins
   every run's results, exactly as Jobs 07/08's evidence checkers
   already do. Verdicts, ops, exit codes and on-node behavior are
   unchanged (regression run + inertness checks above).

## Known gaps and risks

- **The raft-meta rename is not directory-fsynced** (source-level,
  out of lazyfs's model — lazyfs passes renames through). On a real
  power loss, a filesystem that has not journaled the rename may
  recover the *previous* `raft-meta` — an old term/votedFor — which is
  precisely the re-vote hazard, filesystem- and timing-dependent
  (etcd/ZooKeeper fsync the parent dir for exactly this reason). Not
  demonstrable with this harness; flagged for the coordinator as a
  possible upstream question rather than asserted as a defect.
- **arm64 remains untested** for lazyfs (PLAN Q8 stance kept): the
  image builds without the binary there and durability runs fail
  loudly at mount proof; every other scenario is unaffected.
- **The armed torn path goes stale if a segment roll lands between
  arming and fire.** Rolls happen on term change, at 8 MB — and on
  EVERY restart: reopening the log rolls the recovered in-progress
  segment to a closed name (the Job 10 spike's own logs show
  `log_inprogress_0` → `log_0-10` + `log_inprogress_11` across one
  restart), and lazyfs keys torn faults on exact paths, so a path
  captured before any of these events targets a file Ratis never
  writes again. That is why the nemesis re-discovers the segment
  inside every arm and never caches it. No roll trigger exists inside
  the shipped one-shot schedule (no other fault runs; sub-second
  window); a miss is a loud evidence-law red, never a silent green;
  and a never-fired tear now records `:armed-segment` /
  `:open-segment-now` / `:armed-path-stale?` in the heal op — quoted
  by the red verdict — so a staled arm names itself. Relevant if
  durability kinds are ever composed with restart- or
  election-causing faults, which is nearly every other kind in this
  harness.
- **lazyfs remains a research prototype**: the pin is load-bearing
  (this job found two fifo-grammar bugs at it); re-verify behavior
  before any bump, and note the fifo write path blocks forever without
  a reader — every harness send is timeout-wrapped, keep it that way.
- **A `:wedged` torn outcome is possible in principle** (victim JVM
  alive but never emitting a startup line on torn storage); it is
  recorded as such and the checkers grade its client-visible effects,
  but it was not observed — if it ever appears it deserves its own
  triage.
- **Cache sizing is a silent-weakening hazard if changed casually**: a
  cache smaller than the touched byte-set makes lazyfs write through
  (eviction off) and quietly defuses the drop faults. 128 MiB is ~4×
  the observed worst case; the sizing rationale lives on
  `db/lazyfs-cache-size`.

## Suggestions (out of scope)

- **Upstream to lazyfs** (dsrhaslab/lazyfs): the two fifo torn-op bugs
  (`occurrence=` rejected vs. its README; "configured successfully"
  logged for dropped faults). Repro commands and the misleading-success
  store are in this repo.
- **Coordinator question for the Ratis engagement**: whether the
  missing parent-dir fsync after the `raft-meta` rename merits an
  upstream report (it is the one durability edge this harness cannot
  reach; a dm-flakey/CrashMonkey-style follow-up could).
- **Delete `.github/workflows/fuse-spike.yml`** (Job 10's own
  suggestion): the real M4 workflow tokens now exist; the spike file is
  outside this job's ownership so it was left in place.
- **A `torn-write` variant targeting the state-machine snapshot file**
  during `takeSnapshot` would exercise the snapshot-side durability
  contract the same way (BACKLOG 1's write-to-temp-then-rename overlaps
  here).
- **elle migration** (PLAN M1's original intent) would remove the
  knossos `:info` cliff that forced drop-all's sizing; the counter
  workload already sidesteps it.

## Environment notes (this execution sandbox, not the repo)

dockerd started fresh (no reusable images); the env image rebuilt from
scratch with the lazyfs stage. Sandbox-only accommodations, all
uncommitted, per the Job 04 precedent: a locally-tagged `ubuntu:24.04`
shim (https apt sources + the session proxy's CA), `RJ_EXTRA_CA_BUNDLE`
pointed at the proxy CA (the full CCR bundle exceeds run.sh's 64 KiB
pre-flight; the standalone `agent-proxy-ca.crt` fits),
`RJ_DOCKER_BUILD_ARGS="--network=host --build-arg https_proxy=…"`,
control's `/root/.m2` seeded from a host-side `clojure -P` (containers
have no runtime egress), and gnuplot side-loaded into control via an
`apt-get install --download-only` on a host-network throwaway container
(perf plots for parity with prior ledger entries). The GitHub MCP
server handled all GitHub operations.
