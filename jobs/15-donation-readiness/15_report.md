# Job 15 report — donation-readiness assessment (capstone, adversarial)

*Written in role: an Apache Ratis committer, addressed to the party
offering this repository as a donation. Evaluated 2026-08-07 at commit
`3ad2458` (results at their pinned commit `4126b48`), from a clean
checkout, on a 4-core x86_64 Linux box whose only quirk is a
TLS-inspecting egress proxy. Everything quoted below was read, run, or
fetched by me during this evaluation; file:line references into Apache
Ratis are against the `ratis-3.2.2` tag.*

## 1. Verdict: accept with required changes

I tried to break this and mostly failed. The harness runs from a clean
checkout as documented; the committed evidence is internally consistent
down to individual history indexes and externally consistent with the
public CI jobs it links; the checker machinery is not self-certifying —
it demonstrably convicts a seeded liar (I reproduced that locally) and
refuses green runs whose faults didn't happen; and all four claims about
Ratis itself survived checking against the actual 3.2.2 source, at the
level of specific lines. That is a higher standard of evidence than most
test-harness donations arrive with, and I want to say so once, plainly,
before spending the rest of this report on what is wrong.

What is wrong is not structural, but some of it is exactly the kind of
thing that torpedoes a donation thread: the front page contains one
categorically false sentence about upstream interaction history; the
repository has no LICENSE or NOTICE file — a defect it has known about
for three days and shipped anyway; the fact that this codebase was
written almost entirely by AI agent sessions is discoverable in thirty
seconds from `git log` but stated plainly nowhere a maintainer will
look; and the harness's own unit test suite — the tests for the code
that decides convictions — cannot be run in the environment the project
ships, and runs in no CI. Fix the numbered list below before any offer
is made. None of it is more than days of work.

## 2. Required before the offer is made

Ordered by how badly each would damage reception if a maintainer found
it before you disclosed it.

1. **Fix the false front-page claim about upstream filings.**
   `README.md` ("Findings about Ratis") says: *"No upstream issues have
   been filed against Ratis from this work."* This is not true.
   [RATIS-2640](https://issues.apache.org/jira/browse/RATIS-2640)
   ("AdminApi.setConfiguration(RaftPeer[], RaftPeer[]) drops the servers
   array and always throws NullPointerException") was filed on
   **2026-08-04 by the owner of this repository** (reporter matches the
   GitHub account hosting this repo) and is now Resolved/Fixed. Your own
   `harness/src/ratis_jepsen/client.clj` says so: *"the (RaftPeer[],
   RaftPeer[]) convenience overload is broken at 3.2.2 (RATIS-2640, our
   upstream find — … fixed on master, not in 3.2.2)"*. A Ratis
   committer evaluating this offer can — as I did — look up the
   reporter in two minutes, and from that moment every other claim in
   the README reads differently. The fix is easy because the truth is
   *better* than the claim: you found a real setConfiguration bug, filed
   it properly, and it got fixed. Say that. (If the sentence was meant
   as "none of the four headline findings has been filed yet," write
   that sentence instead.)

2. **Add `LICENSE` and `NOTICE` files at the repository root.** Your own
   backlog has carried this since 2026-08-05 (BACKLOG 15, "re-reported
   by Job 13", still open at the evaluated commit). Every source file
   carries the Apache-2.0 header (I swept all tracked `.clj`, `.java`,
   `.sh`, `.yml`, `.xml`, plus `Dockerfile` and `ssh_config` — zero
   misses), and `README.md` states the license, but an IP donation
   offer from a repository with **no LICENSE file** is a self-inflicted
   wound: it is the first thing an ASF-minded reviewer checks, and it
   signals that the donor's own checklist wasn't finished. The NOTICE
   should also credit the studied prior art (sofa-jraft-jepsen is named
   in the README as shape-only prior art; PLAN Q17 already called for
   exactly this).

3. **Disclose how this was built, on the front page, before anyone asks.**
   `git log` shows 45 commits, 32 of them co-authored by Claude
   (`Co-Authored-By: Claude …`); `docs/PROCESS.md` describes the
   coordinator, every worker, and every reviewer as "agent sessions",
   with one human (the owner) assigning and arbitrating; `CLAUDE.md`
   says "built by a coordinated multi-agent process." The front-page
   README's pointer table euphemizes this as "coordinated jobs +
   adversarial reviews" and never uses the words "AI" or "agent". I do
   not think the provenance is disqualifying — the review record is
   substantive (3,563 lines of review reports, two REVISE verdicts, and
   Review 05's false-red root-cause that materially changed the outcome
   map — that is not rubber-stamping). But the ASF has a Generative
   Tooling policy, maintainers have opinions, and this fact is
   *instantly discoverable*. Being told up front reads as confidence;
   being discovered reads as concealment. Add a short provenance
   section: who (one human owner + named AI tooling), how (the
   job/review process, link PROCESS.md), and what human verification
   the results got. Decide at the same time **what is actually being
   donated** — the harness + results, or also the ~13,000 lines of
   internal process record under `jobs/` and `reviews/` (the process
   record is currently *larger than the codebase*, 13.1k lines of md vs
   9.9k of Clojure+Java). Either answer is defensible; the offer should
   state one.

4. **Make the harness's own test suite runnable in the shipped
   environment, and run it in CI.** Today it is neither. The documented
   command (`harness/README.md`: "Then, from `harness/`:
   `clojure -M:test`") fails inside the `control` container:

   ```
   Error building classpath. Unable to clone
   https://github.com/cognitect-labs/test-runner.git …
   ```

   because the `:test` alias declares cognitect test-runner as a **git**
   dependency, the env image ships no git at all, and `run.sh test`
   actually installs a fake `/usr/local/bin/git` that always exits 1 (a
   documented shim for jepsen's provenance logging). So the tests run
   only on a host with Clojure CLI + git + JDK 21 installed natively —
   prerequisites stated nowhere (the front page requires only "Docker
   with the compose plugin"). And no GitHub workflow invokes
   `clojure -M:test`, so the ~2,400 lines of unit tests for
   `outcome.clj` and `checker.clj` — the code that decides whether
   Ratis gets convicted — run in no CI at all. For a maintainer
   this is the worst kind of trap: the correctness core has excellent
   tests that they will not discover are broken-by-environment until
   they try to change something. Fix: vendor test-runner as a Maven dep
   (or add real git to the image), and add a cheap `harness-tests` job
   to the workflow.

5. **File (or pre-draft) the two upstream issues for BACKLOG 7 before
   the offer, or make the offer contingent on filing them together.**
   The strongest technical asset here is the `BaseStateMachine.pause()`
   finding, and it is fully verified (see §4.4) — but arriving on the
   dev list with "we found a bug that kills your integrators' divisions,
   documented in our repo for three weeks, not filed" invites the
   response "why didn't you file it?" The backlog itself already names
   the two issues to file (base-class lifecycle trap; install-retry
   backoff). Filing them *with* the donation offer — each with the
   probe as reproducer — converts your best finding from a private
   trophy into the opening of the collaboration, and RATIS-2640 shows
   you already know how to do this well.

## 3. Would strengthen the case (do separately; not blockers)

1. **Repetition.** The published record is one run per scenario per
   version on one date (the READMEs say so honestly, and the ledger
   holds prior greens), but "green on the first and only attempt" cuts
   both ways: it is honest, and it is n=1. A second dated sweep — or
   ×3 on the historically flaky shapes — would cost ~90 runner-minutes
   and remove the easiest statistical objection.
2. **The knossos ceiling.** Analysis cost varies wildly (your own
   Review 05 measured 23 s vs 20 min 22 s for comparable crash
   histories on one box) and it is why op budgets are capped at 300/key
   and `unsync-drop-all` needs bespoke rate/key tuning. The deferred
   elle migration would retire a whole class of tuning and the cost
   cliff with it. As the donee, I would rank this the highest-value
   engineering item in the repo.
3. **De-fragilize the log-grep evidence patterns.** The evidence
   checkers and the leader/conf censuses match exact 3.2.2 log
   phrasings (`"but logStartIndex = \d+, send snapshot"`,
   `"receive installSnapshot"`, `"set configuration conf: \{index: …
   old=peers:"`, `"changes role from \S+ to LEADER"`). They happened to
   survive unchanged at 3.3.0 RC2, but any upstream log-message edit
   breaks them — loudly where evidence is *required* (good), silently
   zeroing counts everywhere else (bad). At minimum, centralize the
   patterns per version and add a canary that fails fast when a pattern
   matches nothing across a whole sweep.
4. **Ship real `git` and `gnuplot` in the image.** Kills the sudo/git
   shims in `run.sh` (which work, but read as warts to a new reader)
   and the "CI stores have no latency plots" asymmetry in the published
   results.
5. **Surface the corporate-proxy knob at the point of failure.** On a
   TLS-inspected network, first `up` dies ~40 s in with a raw
   `git clone … server certificate verification failed` from the lazyfs
   build stage. The knob (`RJ_EXTRA_CA_BUNDLE`) exists, is documented in
   `env/README.md`, and worked exactly as documented when I used it —
   including the 64 KiB preflight refusing my oversized bundle with the
   promised remedial message, and the multi-cert split handling my
   6-cert bundle. But the Quickstart doesn't mention it, so the first
   experience on such a network is an unexplained TLS error. One
   sentence in the Quickstart fixes it.
6. **Correct the workflow's lazyfs cost comment.** It claims the image
   build "compiles lazyfs (~2–3 min, every job pays it)"; observed CI
   topology-up (which includes that build) took ~76 s per job on the
   published sweep. Overstating your own costs is the benign direction,
   but a maintainer reading the workflow should get true numbers.
7. **arm64.** Untested by the project's own admission, and Ratis
   developers include Apple-silicon laptops. One CI leg or one recorded
   dev-machine run would convert "should work" into a claim.
8. **Fix BACKLOG 1 (the >64 KiB key poisons `takeSnapshot` on every
   node) before donation**, or at least demote the SUT's claim to
   robustness accordingly; a donated SUT with a known
   permanently-poisons-snapshots bug (however unreachable by current
   workloads) will be found by the first fuzzing-minded contributor.
9. **Decide the metadata probe's fate.** `metadata-probe.sh` is
   correctly quarantined ("source-proven; probe-consistent", BACKLOG 11
   hardening pending). Either harden it or exclude it from the offer;
   don't hand it over in its current state without repeating that
   caution in the offer text.

## 4. Findings, with the evidence

### 4.1 Does it work? (Phase 2 — clean-checkout run)

On a clean checkout with Docker 29.3.1:

- `env/run.sh up` — **failed first** on this proxied network (raw git
  TLS error in the lazyfs build stage; see §3.5). With
  `RJ_EXTRA_CA_BUNDLE` set per `env/README.md`, completed in ~7½ min:
  image built, 8 containers up, "all 7 nodes ssh-ready".
- `env/run.sh test --nemesis partition --time-limit 300` — the exact
  Quickstart command. First run ~13 min (Clojure/Maven downloads + SUT
  build + 309 s op phase + analysis). **Exit 0, `Everything looks
  good!`**, 1500 ops = 1090 ok / 410 fail / 0 info — inside the band of
  the published CI run for the same scenario (1096/404/0). Store written
  where documented.
- `env/run.sh test --nemesis partition --seed-bug stale-reads
  --time-limit 120` — the red gate. **Exit 1, `:valid? false`,
  convicted on all five keys.** The harness convicts a lying SUT on my
  machine, not just in the committed record.
- `env/run.sh probe --ratis-version 3.2.2` — reproduced the BACKLOG 7
  library-level result live:
  `PROBE phase=C install-outcome=died follower-division=CLOSED`,
  ~26 s after the leader's log showed the snapshot install renamed
  successfully. A naive `BaseStateMachine` integrator's division dies
  on live install, on my machine, today.
- `env/run.sh down` — clean, idempotent, volumes and network removed.
- `clojure -M:test` — **does not work in the shipped environment**
  (§2.4). The SUT's own Maven tests (in-JVM 3-node cluster) do pass
  during the tarball build.

Verdict on "does it work": yes, with one real environment-class caveat
(proxy CA discoverability) and one real gap (harness unit tests).

### 4.2 Is the committed evidence real? (Phase 3)

I checked every one of the 43 committed `results.edn` files (20 + 21
reference runs + 2 voided) for top-level `:valid?` against the tables:
**all match**, including both expected-reds being red and — notably —
both *voided* runs being green (`:valid? true` / `:unknown`), which
corroborates the claim that they were voided on the collision facts and
not to bury a failure. The voided-runs README is the most honest
incident record I have seen in a test repository: it explicitly labels
a green verdict "uninterpretable, not evidence."

Spot-checks of specific claims, all verbatim matches:

- `snapshot-churn/results.edn`: `:total 2`, `n5 {:send 1}`,
  `n3 {:receive 1}` — the README's "2 install-snapshot events (n5 send
  → n3 receive)".
- `membership/results.edn`: `:transitions 21` with the index list.
- `counter-crash/results.edn`: `:retry-evidence {:total 217, :ops 80,
  :by-f {:add 193, :read 24}}`.
- `membership-snapshot-churn/results.edn`:
  `:joined-with-installs ["n4" "n1" "n7"]`.
- Q14 expected-red: all five per-key first violations match the README
  table to the digit (e.g. key 0 `:double-count :read 118 :lower 116
  :upper 116`), and the run's `:info` count is 0 as claimed — every
  excess unit a proven double-apply.
- Seeded-red history: ops at `:index 297` (`:ok :read [0 1]`) and
  `:index 307` (`:ok :read [0 3]`) are exactly the quoted conviction
  pair; the seed-bug banner is in every committed node log; all five
  knossos conviction SVGs are present.

External consistency: via the GitHub API I verified both CI runs exist
(31205755119 and 31205774470, both dispatched 2026-08-07 at harness
commit `4126b48`, both successful), enumerated the 18 jobs of the 3.2.2
run, and matched **every per-row job link in the README to a real job
ID with the right scenario name**. The claimed 17-CI/3-local split is
exactly what the job list shows. The red-gate CI job requires both a
non-zero harness exit *and* a `:valid? false` in a `results.edn` — an
infrastructure failure cannot impersonate a conviction.

Could a skimmer be misled? The one soft spot is that a green table row
is one 300-second window (stated in "Known limits", but a skimmer won't
read it). The expected-red quarantine, by contrast, is impossible to
misread — directory names, a warning block before any table, and
"RED is the pass condition" repeated.

### 4.3 Is the machinery sound? (checker/classification review)

I read the full checker and outcome stack (~2,300 lines) rather than
trusting the tests.

- **The evidence-assertion design is the right shape**: dedicated runs
  *owe* server-side evidence (install pairs, transitional conf entries,
  joiner receives, lazyfs acks, retry counts, applied rolls) and a run
  that can't produce it fails with a specific error. The negative arms
  aren't hypothetical — the ledger records the checker convicting a
  zero-install churn run and the pre-fix SUT when each was first built.
- **The outcome map is the most dangerous file, and it is handled with
  respect.** The two spot-checks I did against Ratis source both held:
  `LeaderSteppingDownException` has exactly one construction site at
  3.2.2 (`RaftServerImpl.java:791`), write-path only, pre-append —
  so its `:fail` grading is sound; and the NotLeaderException-is-
  ambiguous amendment (a deposed leader completes appended entries with
  NLE and they can commit under the successor) is real, was found by
  their own Review 05 as a false conviction of a healthy cluster, and
  is now both documented and guarded by bounded same-callId retries.
  The residual risk a maintainer inherits: several rows are
  version-pinned claims about Ratis internals that must be re-verified
  each release (the repo knows this; the skew-refusal and probe help).
- **The counter checker's bounds logic is sound** given the outcome
  map's guarantee that `:fail` adds are definitely-not-applied — I
  traced that guarantee for each `:fail`-producing row. The
  `duplicate-observed-value` assertion is a nice repliedIndex probe.
- **The liveness checker** gates on nemesis events conservatively
  (fault-invocation to heal-completion + 15 s grace) and requires
  *continuous* attempts before flagging, so an exhausted generator
  can't fake a stall. Its parameters (60 s window) are stated in every
  results.edn.
- **Weaknesses**: the log-phrase coupling of §3.3; the knossos budget
  ceiling of §3.2; and the fact that none of these unit tests run in
  CI (§2.4).

### 4.4 Are the findings about Ratis real? (Phase 4 — against source)

All four, verified independently against the `ratis-3.2.2` tag:

1. **BACKLOG 7 (pause/install kills divisions) — real, and the chain is
   exactly as claimed.** `BaseStateMachine.pause()` is an empty method
   (`BaseStateMachine.java:103–104`); the install path calls
   `sm.pause()` expecting a state change (`ServerState.java:476`);
   `StateMachineUpdater.reload()` hard-asserts
   `getLifeCycleState() == PAUSED` (`StateMachineUpdater.java:230`).
   I reproduced the division death live with their probe (§4.1). The
   repo's own SUT carries the explicit pause/reinitialize lifecycle
   management that upstream's `SimpleStateMachine4Testing` also
   carries — which is why *their* cluster runs are green and why the
   claim is correctly scoped to base-class integrators.
2. **BACKLOG 9 (staged LISTENER never leaves STARTING) — real,
   mechanism pinned correctly.** The caught-up mark in `checkStaging`
   filters on `containsInConf(f.getId())`
   (`LeaderStateImpl.java:846`), which defaults to FOLLOWER-only
   (`PeerConfiguration.java:122–123`); `initializing` in AppendEntries
   is `!isCaughtUp` (`LeaderStateImpl.java:614`); and the follower
   transitions STARTING→RUNNING only on a non-initializing append
   (`RaftServerImpl.java:1611`). A listener therefore stays STARTING
   forever, exactly as their cluster probe records
   (`ServerNotReadyException … current state is STARTING`, committed in
   both versions' `listener-probe` runs). RATIS-1825 is indeed Open
   (filed 2023 by an unrelated reporter); calling this "corroboration
   with a mechanism and a candidate one-line fix" is accurate, not
   inflated.
3. **BACKLOG 8 (GroupInfoReply conf dropped at 3.2.2, fixed at 3.3.0) —
   both halves check out.** `toGroupInfoReplyProto` at 3.2.2 sets
   group/health/role/commitInfos/logInfo and never `conf`
   (`ClientProtoUtils.java:359–375`) while the proto field and
   `getConf()` exist; current master has
   `reply.getConf().ifPresent(b::setConf)`. "Not a defect to report;
   offer the regression test" is the right framing.
4. **BACKLOG 10 (no parent-dir sync after raft-meta rename) — correctly
   characterized as a question, not a defect.** The meta file write is
   fsynced (`AtomicFileOutputStream` uses force-at-close) and then
   renamed via `FileUtils.move` with no directory fsync anywhere on the
   path — and the repo explicitly states its own harness *cannot*
   demonstrate the consequence (lazyfs passes renames through). This
   discipline — a finding they cannot test, labeled as such, with the
   right follow-up tooling named — is the strongest single credibility
   signal in the repository.

Severity framing throughout is honest: "defect" vs "already fixed" vs
"open question" is maintained everywhere the findings are quoted,
including on the front page. The one accuracy failure in this area is
the upstream-filings sentence (§2.1) — which is about process history,
not the findings themselves.

### 4.5 Does it test Ratis, or its own toy server?

The honest answer, which the repository itself gives in the right
places: it tests **`RaftServer` + `RaftClient` + the gRPC transport as
integrated by a small, deliberately well-behaved embedder**. That means:

- The Raft-machinery results (linearizability under partition/crash/
  pause/transfer, real install-snapshot, committed reconfigurations,
  retry-cache exactly-once and its expiry boundary, torn-log refusal,
  un-synced-loss survival) are results about Ratis code paths — the
  client stack included, which matters (sliding window, retry cache,
  NLE failover are all under test, and RATIS-2640 fell out of exactly
  this).
- Library-level traps that a *naive* integrator hits are invisible to
  the cluster runs, because the SUT works around them — BACKLOG 7 is
  the proof, and the project had to build a separate in-JVM probe to
  see it. The green tables certify "Ratis, integrated the way upstream's
  own test state machine integrates it"; they do not certify
  `BaseStateMachine` out of the box (their words: "these runs neither
  test nor contradict that").
- Blind spots are enumerated where a reader finds them (single Raft
  group, gRPC only, no TLS, no DataStream, no performance, in-memory SM
  with file snapshots, 300 s windows, knossos budgets). I found no
  coverage claim in the README that the artifacts do not back.

So: partial generalization, correctly labeled. A maintainer should
read the greens as regression evidence for the tested integration
shape, and the probes as the mechanism for library-level claims.

### 4.6 The burden (Phase 5 — what Ratis would be signing up for)

- **Stack**: Clojure 1.12 + jepsen 0.3.13 + knossos on the control
  side; Java 21 SUT; Docker compose topology of 8 containers; a pinned
  lazyfs commit built from source (C++/FUSE) in the image; bash
  orchestration; GitHub Actions. A Ratis committer maintaining this
  needs working Clojure — not deep, but real: the checkers and outcome
  map are the files that will need changes when Ratis changes, and
  **Clojure is not a skill the Ratis committer base advertises**. This
  is the single largest adoption risk, and no amount of documentation
  removes it; budget for it explicitly in the offer (e.g. the donor
  commits to N months of maintenance / review availability).
- **Coupling to Ratis internals**: the evidence greps (log phrasings),
  the outcome-map rows (exception semantics), and the probe (lifecycle
  internals) are all version-pinned claims needing re-verification per
  release. The version-skew guard, the red gate re-run every sweep, and
  the probe make this *manageable* — the harness is unusually good at
  refusing to run wrong — but it is recurring work, not a one-time
  cost.
- **CI cost**: the default sweep is 12 jobs (11 scenarios + red gate) ×
  ~7 min ≈ **85 runner-minutes per dispatch**, manual-only by design.
  Cheap on GitHub-hosted runners; the cadence decision transfers to
  Ratis, as intended.
- **Failure modes**: when it fails honestly it is loud and specific
  (evidence errors name what was owed; mount proof aborts; skew
  refuses to start). The silent modes are the non-required evidence
  counts (§3.3) and knossos cost blowups presenting as multi-minute
  analyses (§3.2).
- **Bus factor**: one human owner plus AI sessions produced everything;
  there is no second person anywhere who has maintained this code. The
  process record under `jobs/`/`reviews/` (13k lines) is a genuine aid
  to archaeology — decisions are traceable to dated briefs and review
  findings to an unusual degree — but it is also a nonstandard shape a
  donee must decide whether to keep.
- **When the donor loses interest**: the harness keeps running against
  pinned versions indefinitely (everything is pinned and hermetic);
  what rots first is the log-phrase coupling and the outcome map at the
  next Ratis release that touches either. Without a Clojure-capable
  adopter, that is the moment it becomes shelfware.

## 5. My honest read of the reception

If this lands on dev@ratis tomorrow as-is: the first reply is positive
(RATIS-2542 literally asks for this and has sat empty since May), the
second reply asks "does it test Ratis or your KV toy?", and the third
asks "who maintains a Clojure harness here?" — and then someone opens
`git log`, finds the Claude trailers before finding any statement from
you about them, finds RATIS-2640 filed by you under a README that says
nothing was filed, finds no LICENSE file, and the thread's temperature
drops for reasons that have nothing to do with the harness's quality.
That is the avoidable outcome, and §2 exists to avoid it.

The objection from someone who *has* read it carefully is different and
more respectable: *"one 300-second window per scenario, knossos-capped
histories, a bespoke SUT, and two of the four findings are a base-class
trap and a corroboration of a known issue — is that worth adopting a
Clojure + Docker + FUSE stack we can't staff?"* The strongest honest
answers are: the retry-cache expiry demonstration (Q14) is a
genuinely novel, bracketed, reproducible characterization of a
documented hazard; the red-gate discipline means the greens are worth
more per second than naive greens; and the alternative on the table is
the status quo — nothing. Whether that carries a vote depends mostly
on whether one committer volunteers to own it, which is why my verdict
conditions on the §2 items rather than on more engineering: the
engineering is already good enough to be worth a champion's time; the
presentation defects are what would prevent one from stepping up.

## 6. What I could not evaluate, and what it would take

- **arm64** — no such machine here; the repo's own claim ("should
  build, untested") is all there is. One CI leg or dev run settles it.
- **Long-horizon behavior** — nothing here runs longer than ~320 s; I
  could not assess soak stability, leak behavior, or rare races, and
  neither can the harness in its current shape (it says so).
- **The 3.3.0 RC2 rebuild path after the vote** — the staging repo
  (`orgapacheratis-1182`) is a run-time input and will be dropped on
  promotion; the committed RC2 evidence stays byte-verified against the
  dev-area tarball, but *re-running* those runs verbatim stops being
  possible the day the repo is dropped. Structural to how the ASF
  stages RCs; worth one sentence in the RC2 README.
- **The metadata probe** (`metadata-probe.sh`) — I read it and its
  BACKLOG 11 caveats but did not run it; the repo itself says its
  decision rules need hardening before external quotation, and I
  treated that self-assessment as binding.
- **knossos-vs-elle equivalence** on these histories — I verified the
  checker convicts and clears correctly on the committed and local
  evidence; I did not independently validate knossos's search against
  another checker, and the repo's op-budget caps exist precisely
  because nobody has done the elle migration.
- **ASF process mechanics** (Software Grant vs. incubation shape, IP
  clearance for AI-co-authored code under current ASF policy) — a
  question for ASF counsel and the Ratis PMC, not for this evaluation;
  §2.2/§2.3 are the prerequisites either way.

---

*Everything in §4 was verified during this evaluation: commands run on
a clean checkout (up / test / seeded-red / probe / down / `-M:test`),
all 43 committed `results.edn` files checked against their tables, both
CI runs and all 18 jobs of the 3.2.2 sweep resolved through the GitHub
API, and every Ratis source claim read at the cited file and line at
tag `ratis-3.2.2` (plus current master for the BACKLOG 8 fix, and
Apache JIRA for RATIS-1825/RATIS-2640).*
