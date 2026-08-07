;; Copyright 2026 the ratis-jepsen authors.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ratis-jepsen.nemesis
  "Fault schedules (Job 05 M1 + Job 07 M2 part 1 + Job 08 M2 part 2 +
  Job 11 M4): none | partition | crash | pause | mixed | snapshot-churn |
  transfer | membership | membership-snapshot-churn | listener-probe |
  quorum-pause | mixed-all | unsync-drop | unsync-drop-all | torn-write.

  Vocabulary — every nemesis op :f is unique across fault kinds, so one
  composed nemesis routes ops in every mode and history event names never
  depend on which --nemesis was chosen:

    :start / :stop         random-halves network partition (Job 04, unchanged)
    :crash / :restart      kill -9 a random minority (leader-biased), restart
    :pause / :resume       SIGSTOP a random minority, SIGCONT
    :churn-kill / :churn-restart
                           snapshot churn's fault pair: kill one follower,
                           later restart it — after the two actions below
                           its only recovery path is install-snapshot
    :churn-transfer        ACTION inside the churn cycle: transfer
                           leadership to a live non-leader. The term bump
                           is what makes the purge REAL: segments only
                           roll at 8 MB (unreachable here) or on a term
                           change (SegmentedRaftLog.appendEntryImpl), and
                           purge drops closed segments only
                           (SegmentedRaftLogCache.purge) — without this
                           step the whole run lives in one open segment,
                           nothing purges, and the dead follower catches
                           up from the log instead of install-snapshot
                           (observed live; source-verified at 3.2.2)
    :churn-snapshot        ACTION: ask every live server to snapshot now
                           (SnapshotManagementApi), which with
                           purge.upto.snapshot.index=true purges the
                           now-closed old-term segments past the dead
                           follower (subject to purge.gap = 1024: cycles
                           less than ~1024 indexes after the last purge
                           skip it and catch up from the log — several
                           cycles per run do purge)
    :transfer              ACTION, not a fault: AdminApi.transferLeadership
                           to a random voter; a refused/timed-out handover
                           is a legal recorded outcome

  Membership churn (Job 08 — the L3-orchestrator rehearsal; drives the
  RATIS-1912/RATIS-2274 defect class). Moves are picked at generation
  time; legality (voter band 5±2, floor 3, pool availability) is checked
  at invocation against the shared membership state and illegal moves
  are recorded as skips:

    :member-add            ACTION: bootstrap a pool node
                           (GroupManagementApi.add with the empty-peers
                           group) and commit it into the conf
                           (setConfiguration COMPARE_AND_SET, voters up
                           to 7)
    :member-remove         ACTION: commit a conf without one voter, then
                           kill+wipe(storage)+restart it in --join mode —
                           back to the clean pool (voters down to the
                           floor of 3)
    :member-replace-dead / :member-replace-done
                           the L3 rehearsal sequence, as a fault pair:
                           kill a voter, commit a conf without it (the
                           cluster runs degraded between the two ops —
                           this IS the fault window); then bootstrap a
                           pool node in its place, commit it, and
                           wipe+restart the corpse as pool
    :listener-add / :listener-census / :listener-promote /
    :listener-demote / :listener-remove
                           ACTIONS of the bounded listener-staging probe
                           (RATIS-1825 territory): stage a pool node as
                           LISTENER, census its replication from its log,
                           promote it to voter, demote it back, remove
                           it. Scripted once per run, never randomized.

  Durability faults (Job 11, M4 — need the lazyfs storage topology,
  which their kinds force on; see ratis-jepsen.db):

    :unsync-drop / :unsync-restart
                           the simulated power loss on a minority:
                           kill -9 each target, then discard its
                           un-synced lazyfs cache (fault ordering B from
                           the Job 10 spike — process death first, then
                           the storage loses what was never fsynced),
                           later restart. Expectation GREEN: Ratis
                           fsyncs each append before acknowledging, so
                           nothing acknowledged is droppable
    :unsync-drop-all / :unsync-restart-all
                           the same fault on EVERY voter simultaneously
                           — the whole-cluster power loss, where Raft's
                           durability assumption is actually
                           load-bearing. Expectation GREEN on safety; a
                           temporary availability gap is legal and the
                           liveness checker gates it via fault->heal
                           like any other window
    :torn-write / :torn-restart
                           lazyfs torn-op on one follower's current open
                           log segment, re-discovered at arm time (never
                           cached: Ratis rolls the open segment on EVERY
                           restart, not just term change/8 MB — see
                           db/current-open-segment!): the NEXT write to
                           it is split into parts of which exactly one
                           (--torn-persist-part) reaches the backing
                           store, then lazyfs SIGKILLs itself — a power
                           loss mid-write, torn at sub-page granularity.
                           The heal kills the SUT, remounts lazyfs over
                           the torn backing store (re-proving the mount)
                           and restarts: the node must either recover
                           cleanly or refuse loudly (CorruptionPolicy
                           EXCEPTION is the default); either outcome is
                           recorded, a refusal does NOT fail the heal.
                           A tear that never fired records armed-vs-now
                           segment forensics in the heal op, so a stale
                           armed path is diagnosable from the history.
                           Scripted ONCE per run: each tear may legally
                           cost a node until the run ends, so repeating
                           it would walk the cluster below its majority
                           and convict Ratis for our own fault schedule

  Each kind's schedule is a self-contained *segment* — calm sleep, then
  fault/action ops, ending healed — so a run is a concatenation of
  segments that always starts calm and always heals one fault before the
  next begins (in mixed modes a pause can therefore never land on a node
  a previous crash left dead). `mixed` draws uniformly from the three M1
  kinds (unchanged); `mixed-all` draws uniformly from six (the M1 three,
  snapshot churn, transfer, and a random membership move) — the
  durability kinds stay OUT of mixed-all: they are an opt-in storage
  topology (the mount changes every node's storage stack and its
  startup budget), not a fault to draw at random (Job 10 spike,
  recommendation 1).

  Fault windows for the liveness checker (ratis-jepsen.checker) are
  derived from fault->heal: a window opens at a fault op's invocation and
  closes at its heal op's completion. Action fs (:churn-snapshot,
  :transfer, :member-add, ...) appear in histories but never gate
  liveness — staged catch-up of a joining node runs in the leader's
  background and must not excuse a stalled majority, so only
  :member-replace-dead (a genuinely dead voter) opens a window."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.db :as jdb]
            [jepsen.generator :as gen]
            [jepsen.nemesis :as jn]
            [ratis-jepsen.client :as client]
            [ratis-jepsen.db :as db]
            [ratis-jepsen.env-contract :as env])
  (:import (org.apache.ratis.client RaftClient)
           (org.apache.ratis.protocol.exceptions AlreadyExistsException
                                                 SetConfigurationException)))

(def kinds
  "CLI-selectable fault schedules."
  #{"none" "partition" "crash" "pause" "mixed"
    "snapshot-churn" "transfer" "membership" "membership-snapshot-churn"
    "listener-probe" "quorum-pause" "mixed-all"
    "unsync-drop" "unsync-drop-all" "torn-write"})

(def durability-kinds
  "The fault schedules that inject storage-durability faults through
  lazyfs (Job 11, M4). core.clj forces the --durability topology on for
  these — an un-synced-drop against the plain filesystem would silently
  test nothing."
  #{"unsync-drop" "unsync-drop-all" "torn-write"})

(def fault->heal
  "Each fault-opening op :f and the op :f that heals it. The single
  source of truth for the nemesis vocabulary; the liveness checker's
  nemesis-aware gating is built from this map (which is how
  unsync-drop-all's whole-cluster availability gap is legal: its window
  spans fault invocation to heal completion plus grace, like any
  other)."
  {:start               :stop
   :crash               :restart
   :pause               :resume
   :churn-kill          :churn-restart
   :member-replace-dead :member-replace-done
   :quorum-pause        :quorum-resume
   :unsync-drop         :unsync-restart
   :unsync-drop-all     :unsync-restart-all
   :torn-write          :torn-restart})

(def fault-fs (set (keys fault->heal)))
(def heal-fs  (set (vals fault->heal)))

(def action-fs
  "Nemesis ops that act on the cluster without opening a fault window:
  they never enter fault->heal and the liveness checker ignores them.
  Membership adds/removes qualify: the conf change itself keeps a
  healthy majority throughout (staged catch-up is leader-side
  background; a removed node leaves a full-capacity smaller conf)."
  #{:churn-transfer :churn-snapshot :transfer
    :member-add :member-remove
    :listener-add :listener-census :listener-promote :listener-demote
    :listener-remove})

;; ---------------------------------------------------------------------------
;; Cycles (seconds). Calm first: runs open with a calm window (Job 04
;; convention), and every segment ends healed. Crash and pause cycles are
;; configurable from the CLI (--crash-calm &c.); the partition cycle is
;; pinned — Job 04 behavior, unchanged.
;; ---------------------------------------------------------------------------

(def partition-cycle
  "15 s healed, 15 s partitioned — Job 04 / DESIGN 2.5, unchanged."
  {:calm-s 15 :fault-s 15})

(def default-crash-cycle
  "Brief default: calm 20 s, then kill a minority for 10 s, restart."
  {:calm-s 20 :fault-s 10})

(def default-pause-cycle
  "Brief default: running 25 s, then SIGSTOP a minority for 5 s, SIGCONT."
  {:calm-s 25 :fault-s 5})

(def default-churn-cycle
  "Snapshot churn: calm 15 s; kill a follower; 2 s later transfer
  leadership (the term bump closes every log's open segment); 5 s of
  writes landing in the new term; snapshot every live server (purging
  the closed old-term segments the dead follower still needs); 3 s
  more; restart it — install-snapshot is now its only way back on
  purge cycles."
  {:calm-s 15 :kill-to-transfer-s 2 :transfer-to-snapshot-s 5
   :snapshot-to-restart-s 3})

(def default-transfer-cycle
  "Leadership transfer: one transfer to a random voter every 20 s."
  {:calm-s 20})

(def default-membership-cycle
  "Membership churn: calm 15 s before each move; a replace-dead's victim
  stays dead-and-removed for 8 s before the replacement half runs."
  {:calm-s 15 :replace-dead-s 8})

(def quorum-pause-cycle
  "The Job 09/Q14 quorum stall: calm 20 s, then every node EXCEPT the
  leader is SIGSTOPped for 8 s. The live leader keeps accepting and
  APPENDING client writes but cannot commit them (no quorum), which is
  the one process-level fault that mass-produces the
  applied-later-but-reply-lost shape the retry-cache expiry boundary
  needs: clients time out mid-stall, the stalled entries commit at
  resume, and a sufficiently delayed same-callId retry meets an expired
  cache entry. (Kill and plain pause cannot produce this population —
  their append-to-reply window is millisecond-scale; measured across
  three Q14 attempts, ledger.) Pinned cycle — a Q14 lever, not a
  general fault-soup member."
  {:calm-s 20 :fault-s 8})

(def unsync-drop-cycle
  "The minority power-loss cycle (Job 11): calm 20 s; kill -9 a random
  minority and drop each one's un-synced lazyfs cache; down 10 s;
  restart. Pinned (like partition and quorum-pause) — the cycle shape is
  not what these runs vary."
  {:calm-s 20 :fault-s 10})

(def unsync-drop-all-cycle
  "The whole-cluster power-loss cycle (Job 11): calm 70 s; kill -9 EVERY
  voter and drop every cache; 5 s of dead air; restart all. Pinned, and
  sized by the knossos budget, not politeness: every write invoked
  during a total outage ends :info (honestly ambiguous — the dead
  leader may have appended it and it can commit after restart), each
  :info stays forever-concurrent in the linear checker, and a thread
  produces one such write per ~5 s of outage (the invocation timeout)
  REGARDLESS of rate — so per-key :info mass = windows × outage-seconds
  / timeout × threads-per-key. Two shakedowns OOMed analysis on every
  key before this shape (8 windows/135 :info at calm 25, 4 windows/
  18–24 per key at calm 50 with 2 threads/key; stores
  …unsync-drop-all/20260807T095908.783Z and …T101150.734Z, preserved).
  calm 70 + window 5 (3 losses/300 s — the drop is instantaneous at
  kill time, dead air proves nothing) with the kind's key-count 10
  default (1 thread/key — core/workload-defaults) lands ~5 :info/key,
  well inside the M0-proven budget."
  {:calm-s 70 :fault-s 5})

(def torn-write-cycle
  "The torn-write script (Job 11): 30 s of calm writes so the victim's
  open segment carries real traffic; arm the tear (it fires within the
  next few appends); 10 s for the fire and the dead-storage window;
  then the kill + remount + restart attempt. Runs ONCE per run — see
  the ns docstring."
  {:calm-s 30 :fault-s 10})

(defn cycles
  "The per-kind cycles for a run: partition pinned (Job 04), the rest
  from the parsed CLI options where present, brief defaults where not."
  [opts]
  {:partition partition-cycle
   :crash     {:calm-s  (:crash-calm-s opts (:calm-s default-crash-cycle))
               :fault-s (:crash-fault-s opts (:fault-s default-crash-cycle))}
   :pause     {:calm-s  (:pause-calm-s opts (:calm-s default-pause-cycle))
               :fault-s (:pause-fault-s opts (:fault-s default-pause-cycle))}
   :churn     {:calm-s (:churn-calm-s opts (:calm-s default-churn-cycle))
               :kill-to-transfer-s
               (:churn-kill-to-transfer-s opts
                (:kill-to-transfer-s default-churn-cycle))
               :transfer-to-snapshot-s
               (:churn-transfer-to-snapshot-s opts
                (:transfer-to-snapshot-s default-churn-cycle))
               :snapshot-to-restart-s
               (:churn-snapshot-to-restart-s opts
                (:snapshot-to-restart-s default-churn-cycle))}
   :transfer  {:calm-s (:transfer-calm-s opts
                        (:calm-s default-transfer-cycle))}
   :membership {:calm-s (:membership-calm-s opts
                         (:calm-s default-membership-cycle))
                :replace-dead-s
                (:membership-replace-dead-s opts
                 (:replace-dead-s default-membership-cycle))}})

;; ---------------------------------------------------------------------------
;; Membership state (Job 08). One atom, created by core.clj and carried in
;; the test map as :membership-state, shared by the membership nemesis
;; (which mutates it), db.clj (start mode per node) and the targeting
;; helpers below (fault targets should be conf members, not dormant pool
;; nodes):
;;
;;   {:voters  #{...}   the nemesis's intended current voter set
;;    :pool    #{...}   clean nodes available to add (running --join,
;;                      empty storage)
;;    :dynamic #{...}   nodes started in --join mode: the initial pool
;;                      plus every node that has been through it (grows
;;                      monotonically; --join recovers existing storage,
;;                      so it stays correct across crash restarts)}
;;
;; The intended sets are BELIEF, reconciled against the log-census conf
;; on every successful setConfiguration — never used for correctness,
;; only for target selection; a stale belief costs a skipped or retried
;; move.
;; ---------------------------------------------------------------------------

(def min-voters
  "The conf floor: no move may take the voter count below this (band
  5±2, floor pinned by the brief)."
  3)

(def max-voters
  "The conf ceiling: adds stop at the full 7-node topology."
  7)

(defn initial-membership-state
  "The starting state over a node list: the DESIGN 2.6 initial voters
  that are present, everything else pool."
  [nodes voters]
  (let [nodes  (set nodes)
        voters (set (filter nodes voters))]
    {:voters  voters
     :pool    (set/difference nodes voters)
     :dynamic (set/difference nodes voters)}))

(defn conf-nodes
  "The nodes faults should target: the intended voter set when this run
  carries membership state (pool nodes host nothing worth crashing into),
  the whole node list otherwise (M0/M1 behavior, unchanged)."
  [test]
  (if-let [state (:membership-state test)]
    (vec (sort (:voters @state)))
    (vec (:nodes test))))

;; ---------------------------------------------------------------------------
;; Target selection
;; ---------------------------------------------------------------------------

(def leader-bias
  "Probability that a :crash target set is forced to include the current
  leader (the brief's \"biased to include the current leader some of the
  time\"). Census failures degrade to unbiased selection."
  0.5)

(defn max-minority
  "The largest number of nodes that can be down with the survivors still
  a majority: n - majority(n). 2 for the 5-voter group."
  [n]
  (- n (inc (quot n 2))))

(defn target-count
  "How many nodes a fault should hit: uniform 1..max-minority (so single
  kills — including bare leader kills — stay common)."
  [n]
  (inc (rand-int (max 1 (max-minority n)))))

(defn select-targets
  "Pure minority selection: `size` distinct nodes from `nodes`, with
  `leader` (when non-nil and a member of `nodes`) forced into the set.
  Randomness comes only from shuffle; callers own the size and bias
  rolls, which keeps this testable."
  [nodes leader size]
  (let [size (min size (count nodes))]
    (if (and leader (some #{leader} nodes))
      (->> (remove #{leader} nodes)
           shuffle
           (take (dec size))
           (cons leader)
           vec)
      (->> nodes shuffle (take size) vec))))

(defn crash-targets!
  "The nodes a :crash op kills: a random minority of the conf members
  (all test nodes without membership state — M1 behavior unchanged),
  forced to include the current leader with probability leader-bias.
  The leader census is best-effort (db/current-leaders! greps logs over
  ssh); any failure or empty census degrades to unbiased selection."
  [test]
  (let [nodes  (conf-nodes test)
        leader (when (< (rand) leader-bias)
                 (try (let [ls (db/current-leaders! test)]
                        (when (seq ls) (rand-nth ls)))
                      (catch Exception e
                        (log/warn "leader census failed — unbiased crash"
                                  "targets this cycle:" (.getMessage e))
                        nil)))]
    (select-targets nodes leader (target-count (count nodes)))))

(defn pause-targets!
  "The nodes a :pause op stops: a random minority of the conf members,
  unbiased."
  [test]
  (let [nodes (conf-nodes test)]
    (select-targets nodes nil (target-count (count nodes)))))

(defn quorum-pause-targets!
  "The nodes a :quorum-pause op stops: every conf member EXCEPT the
  censused leader(s) — the whole follower set, so the leader loses its
  quorum while staying up. A failed or empty census degrades to
  all-but-one-random-node (still a quorum stall if the survivor leads;
  a wasted cycle otherwise, recorded either way)."
  [test]
  (let [nodes   (conf-nodes test)
        leaders (try (let [ls (db/current-leaders! test)]
                       (when (seq ls) (set ls)))
                     (catch Exception e
                       (log/warn "quorum-pause: leader census failed:"
                                 (.getMessage e))
                       nil))
        keep    (or leaders #{(rand-nth nodes)})]
    (vec (remove keep nodes))))

;; ---------------------------------------------------------------------------
;; Nemeses. Faults act through the db primitives (jepsen.db Kill/Pause on
;; (:db test)); heals act on every node — start!/resume! are no-ops on
;; nodes already running.
;; ---------------------------------------------------------------------------

(defn crash-nemesis
  "Responds to :crash by kill -9 on a leader-biased random minority, and
  to :restart by starting the SUT on every node (RECOVER semantics are
  the SUT's; restart reuses the exact db.clj start path)."
  []
  (reify jn/Nemesis
    (setup! [this _test] this)

    (invoke! [_this test op]
      (case (:f op)
        :crash   (let [targets (crash-targets! test)]
                   (assoc op :value
                          (c/on-nodes test targets
                                      (fn [t node] (jdb/kill! (:db t) t node)))))
        :restart (assoc op :value
                        (c/on-nodes test
                                    (fn [t node] (jdb/start! (:db t) t node))))))

    (teardown! [_this _test])))

(defn pause-nemesis
  "Responds to :pause by SIGSTOP on a random minority, and to :resume by
  SIGCONT on every node; :quorum-pause/-resume (Job 09/Q14) SIGSTOP the
  whole follower set instead — see quorum-pause-targets!. Teardown
  resumes everything so a run cut mid-pause still tears down (and
  flushes logs) from running processes."
  []
  (reify jn/Nemesis
    (setup! [this _test] this)

    (invoke! [_this test op]
      (case (:f op)
        :pause  (let [targets (pause-targets! test)]
                  (assoc op :value
                         (c/on-nodes test targets
                                     (fn [t node] (jdb/pause! (:db t) t node)))))
        :quorum-pause
        (let [targets (quorum-pause-targets! test)]
          (assoc op :value
                 (c/on-nodes test targets
                             (fn [t node] (jdb/pause! (:db t) t node)))))
        (:resume :quorum-resume)
        (assoc op :value
               (c/on-nodes test
                           (fn [t node] (jdb/resume! (:db t) t node))))))

    (teardown! [_this test]
      (try (c/on-nodes test (fn [t node] (jdb/resume! (:db t) t node)))
           (catch Exception e
             (log/warn "pause-nemesis teardown resume failed:"
                       (.getMessage e)))))))

;; ---------------------------------------------------------------------------
;; Durability nemeses (Job 11, M4). All storage-side machinery — cache
;; drops, torn-write arming, remounting, the mount evidence law — lives in
;; ratis-jepsen.db; this nemesis owns targeting, ordering and recording.
;; ---------------------------------------------------------------------------

(defn- drop-unsynced!
  "The per-node power loss, in fault ordering B (Job 10 spike): kill -9
  the SUT first (power cut), then discard everything its storage never
  fsynced. Returns the per-node record."
  [t n]
  (jdb/kill! (:db t) t n)
  {:killed true
   :drop   (db/clear-cache!)})

(defn- startup-lines!
  "How many contract startup lines the current node's SUT log holds —
  restart detection must count NEW lines, because earlier lines from the
  same run still match any whole-log grep."
  []
  (let [out (try (c/exec :bash :-c
                         (str "grep -c 'ratis-kv server started: ' "
                              env/log-file " 2>/dev/null || true"))
                 (catch Exception _ "0"))]
    (try (Long/parseLong (str/trim out))
         (catch Exception _ 0))))

(def torn-restart-await-ms
  "How long the torn-write heal waits for the victim to either emit a
  NEW startup line (recovered) or exit (refused). Bounded: a refusal is
  a legal recorded outcome, not a run failure."
  30000)

(defn- await-start-or-refusal!
  "After a restart attempt on a torn store: polls until a new startup
  line appears (:started), the process dies (:refused-start), or the
  deadline passes (:wedged). Never throws — every outcome is the
  recorded result of the experiment."
  [before-count]
  (let [deadline (+ (System/nanoTime) (* torn-restart-await-ms 1000000))]
    (loop []
      (cond
        (> (startup-lines!) before-count)
        {:outcome :started}

        (false? (cu/daemon-running? db/pid-file))
        {:outcome  :refused-start
         :log-tail (try (c/exec :bash :-c (str "tail -n 4 " env/log-file
                                               " 2>/dev/null || true"))
                        (catch Exception _ ""))}

        (< (System/nanoTime) deadline)
        (do (Thread/sleep 500) (recur))

        :else
        {:outcome  :wedged
         :log-tail (try (c/exec :bash :-c (str "tail -n 4 " env/log-file
                                               " 2>/dev/null || true"))
                        (catch Exception _ ""))}))))

(def torn-parts
  "How many equal parts the torn write is split into (exactly one part
  persists; which one is --torn-persist-part)."
  3)

(def default-torn-persist-part
  "Which third of the torn write reaches the backing store when the CLI
  does not say: part 1 — the head survives, the tail dies with the
  cache, the truest sequential-power-loss shape. Part 2 instead leaves
  a zero hole before surviving bytes, which biases recovery toward the
  loud CorruptionPolicy=EXCEPTION refusal arm (both are legal recorded
  outcomes; see db/torn-write-command)."
  1)

(defn durability-nemesis
  "Routes the durability fs. :unsync-drop / :unsync-drop-all kill and
  cache-drop a minority / every voter (per-node results recorded; a
  :send-failed drop is visible in the op AND in the evidence checker,
  which fails a dedicated run that never dropped anything).
  :torn-write arms lazyfs's torn-op on one follower's open segment
  (re-discovered at arm time — segments roll on every restart);
  :torn-restart re-collects the evidence, remounts the torn store and
  restarts the victim, recording :started / :refused-start / :wedged as
  a legal experiment outcome. A tear that never fired additionally
  records :armed-segment / :open-segment-now / :armed-path-stale? —
  taken through the still-live mount BEFORE the kill — so an armed
  path staled by a segment roll convicts itself in the history instead
  of demanding lazyfs-log spelunking. Victim (node + armed segment)
  rides an atom between the segment's ops (segments are atomic in
  every mode)."
  []
  (let [victim (atom nil)]
    (reify jn/Nemesis
      (setup! [this _test] this)

      (invoke! [_this test op]
        (case (:f op)
          :unsync-drop
          (let [nodes   (conf-nodes test)
                targets (select-targets nodes nil
                                        (target-count (count nodes)))]
            (assoc op :value (c/on-nodes test targets drop-unsynced!)))

          :unsync-drop-all
          (assoc op :value
                 (c/on-nodes test (conf-nodes test) drop-unsynced!))

          (:unsync-restart :unsync-restart-all)
          (assoc op :value
                 (c/on-nodes test
                             (fn [t node] (jdb/start! (:db t) t node))))

          :torn-write
          (let [nodes     (conf-nodes test)
                leaders   (try (db/current-leaders! test)
                               (catch Exception e
                                 (log/warn "torn-write: leader census"
                                           "failed:" (.getMessage e))
                                 []))
                followers (or (seq (remove (set leaders) nodes)) nodes)
                target    (rand-nth (vec followers))
                _         (reset! victim {:node target})
                persist-part (long (:torn-persist-part test
                                                      default-torn-persist-part))
                result    (-> (c/on-nodes test [target]
                                          (fn [_t _n]
                                            (if-let [seg (db/current-open-segment!)]
                                              {:segment seg
                                               :persist-part persist-part
                                               :armed   (db/torn-write! seg torn-parts persist-part)}
                                              {:armed :no-open-segment})))
                              (get target))]
            (swap! victim assoc :segment (:segment result))
            (assoc op :value (assoc result :victim target)))

          :torn-restart
          (let [{target :node armed-seg :segment} @victim]
            (reset! victim nil)
            (if-not target
              (assoc op :value {:skip :no-victim})
              (assoc op :value
                     (-> (c/on-nodes test [target]
                                     (fn [t n]
                                       (let [fired? (db/torn-write-fired?)
                                             ;; armed-but-never-fired: lazyfs
                                             ;; is still alive, so census the
                                             ;; open segment through the live
                                             ;; mount (a read — torn-op only
                                             ;; triggers on writes) before
                                             ;; the kill: armed ≠ now means
                                             ;; the path went stale under the
                                             ;; arm (segments roll on every
                                             ;; restart / term change / 8 MB)
                                             forensics
                                             (when-not fired?
                                               (let [now (db/current-open-segment!)]
                                                 {:armed-segment    armed-seg
                                                  :open-segment-now now
                                                  :armed-path-stale?
                                                  (boolean (and armed-seg now
                                                                (not= armed-seg
                                                                      now)))}))
                                             _      (jdb/kill! (:db t) t n)
                                             ;; remount even when the fault
                                             ;; never fired: a fresh lazyfs
                                             ;; clears the armed fault, so
                                             ;; no tear can land at an
                                             ;; uncontrolled later moment
                                             remount
                                             (try (db/remount-lazyfs! n)
                                                  {:proven true}
                                                  (catch Exception e
                                                    {:proven false
                                                     :error  (.getMessage e)}))]
                                         (merge
                                           (if-not (:proven remount)
                                             ;; do NOT restart onto an
                                             ;; unproven store — record it;
                                             ;; the evidence checker fails
                                             ;; the run
                                             {:outcome :remount-unproven
                                              :error   (:error remount)
                                              :fired   fired?}
                                             (let [before (startup-lines!)]
                                               (jdb/start! (:db t) t n)
                                               (assoc (await-start-or-refusal! before)
                                                      :fired fired?)))
                                           forensics))))
                         (get target)
                         (assoc :victim target)))))))

      (teardown! [_this _test]))))

(defn churn-nemesis
  "The snapshot-churn nemesis (Job 07): :churn-kill kills one follower
  (leader census keeps the leader out — best-effort; a stale census that
  kills the leader still churns, just without the held-back-follower
  shape that cycle), :churn-snapshot asks every OTHER node to snapshot
  now through its own admin RaftClient (per-server, as the API requires;
  per-node results recorded, replies never trusted as proof — BACKLOG
  item 5), :churn-restart starts everything back up. The kill target
  rides an atom between the segment's ops; segments are atomic in every
  mode, so no other fault interleaves."
  []
  (let [admin  (atom nil)
        killed (atom nil)]
    (reify jn/Nemesis
      (setup! [this test]
        (reset! admin (client/open-raft-client
                        (client/node-addresses (conf-nodes test))))
        this)

      (invoke! [_this test op]
        (case (:f op)
          :churn-kill
          (let [nodes    (conf-nodes test)
                leaders  (try (db/current-leaders! test)
                              (catch Exception e
                                (log/warn "churn: leader census failed:"
                                          (.getMessage e))
                                []))
                followers (or (seq (remove (set leaders) nodes)) nodes)
                target    (rand-nth (vec followers))]
            (reset! killed target)
            (assoc op :value
                   (c/on-nodes test [target]
                               (fn [t node] (jdb/kill! (:db t) t node)))))

          ;; The term bump that closes every open log segment: hand
          ;; leadership to a live node that is not the dead follower and
          ;; not the current leader — a transfer to the sitting leader
          ;; succeeds WITHOUT changing terms, which silently defeats the
          ;; purge (observed in shakedown: the admin client's
          ;; getLeaderId is null before its first request, so the log
          ;; census is the primary exclusion and getLeaderId the
          ;; fallback).
          :churn-transfer
          (let [^RaftClient c @admin
                nodes   (conf-nodes test)
                leaders (set (concat (try (db/current-leaders! test)
                                          (catch Exception e
                                            (log/warn "churn-transfer census"
                                                      "failed:" (.getMessage e))
                                            []))
                                     (some-> (.getLeaderId c) str vector)))
                cands  (or (seq (remove (conj leaders @killed) nodes))
                           (remove #{@killed} nodes))
                target (rand-nth (vec cands))]
            (assoc op :value
                   {:target target
                    :result (try (client/transfer-leadership! c target)
                                 (catch Throwable t
                                   [:error (.getName (class t))
                                    (.getMessage t)]))}))

          :churn-snapshot
          (let [live (remove #{@killed} (conf-nodes test))]
            (assoc op :value
                   (into {}
                         (map (fn [node]
                                [node
                                 (try (client/snapshot-create! @admin node)
                                      (catch Throwable t
                                        [:error (.getName (class t))
                                         (.getMessage t)]))]))
                         live)))

          :churn-restart
          (do (reset! killed nil)
              (assoc op :value
                     (c/on-nodes test
                                 (fn [t node] (jdb/start! (:db t) t node)))))))

      (teardown! [_this _test]
        (when-let [^RaftClient c @admin]
          (reset! admin nil)
          (.close c))))))

(defn transfer-nemesis
  "The leadership-transfer nemesis (Job 07): :transfer asks the group to
  hand leadership to a random voter via AdminApi.transferLeadership. A
  TransferLeadershipException (refused or timed-out handover) is a legal
  outcome recorded in the op, never a nemesis crash."
  []
  (let [admin (atom nil)]
    (reify jn/Nemesis
      (setup! [this test]
        (reset! admin (client/open-raft-client
                        (client/node-addresses (conf-nodes test))))
        this)

      (invoke! [_this test op]
        (case (:f op)
          :transfer
          (let [target (rand-nth (conf-nodes test))]
            (assoc op :value
                   {:target target
                    :result (try (client/transfer-leadership! @admin target)
                                 (catch Throwable t
                                   [:error (.getName (class t))
                                    (.getMessage t)]))}))))

      (teardown! [_this _test]
        (when-let [^RaftClient c @admin]
          (reset! admin nil)
          (.close c))))))

;; ---------------------------------------------------------------------------
;; Membership nemesis (Job 08). All Ratis interop goes through fresh
;; per-operation admin clients built over the CURRENT conf members (plus
;; the bootstrap target where applicable): membership churn is exactly the
;; situation where a long-lived client's peer belief rots, and admin
;; operations are rare enough that a fresh client per move is cheap.
;; Every exceptional outcome is recorded in the op value —
;; ReconfigurationInProgressException, ReconfigurationTimeoutException,
;; SetConfigurationException (CAS mismatch), LeaderSteppingDownException
;; (transfer-window rejection) and transport errors are all LEGAL
;; outcomes of asking a live cluster to reconfigure, never nemesis
;; crashes.
;; ---------------------------------------------------------------------------

(defn- error-token
  "A serializable [:error class message] token for an op value."
  [^Throwable t]
  [:error (.getName (class t)) (.getMessage t)])

(defn- already-exists?
  "Is this (possibly wrapped) throwable the AlreadyExistsException a
  repeated GroupManagementApi.add on the same node raises? That means an
  earlier attempt already bootstrapped the division — success for our
  purposes."
  [^Throwable t]
  (boolean (some #(instance? AlreadyExistsException %)
                 (take-while some? (iterate #(when % (.getCause ^Throwable %))
                                            t)))))

(defn- census-conf
  "The current conf for CAS arguments: the log census when it answers
  (db/conf-census! — highest 'set configuration' index across nodes),
  else the intended membership state (a cluster too young to have logged
  a conf, or an ssh hiccup). Belief either way; a stale answer costs a
  recorded CAS mismatch and a retry."
  [test state]
  (or (try (db/conf-census! test)
           (catch Exception e
             (log/warn "membership: conf census failed:" (.getMessage e))
             nil))
      {:index     :intended
       :servers   (vec (sort (:voters state)))
       :listeners []
       :stable?   true}))

(defn- attempt-set-conf!
  "One COMPARE_AND_SET setConfiguration against `census`, with the new
  conf computed by (transform census) -> {:servers [...] :listeners
  [...]}. Routed through the census's servers plus `extra-route` (the
  bootstrap target, which the client must know the address of). Returns
  {:success? true, :committed target-conf, :census census} or an error
  map ({:cas-mismatch msg} for the retryable staleness case)."
  [census transform extra-route]
  (let [target (transform census)
        route  (vec (distinct (concat (:servers census) extra-route)))]
    (try
      (with-open [^RaftClient c (client/open-raft-client
                                  (client/node-addresses route))]
        (let [r (client/set-configuration-cas!
                  c {:servers           (:servers target)
                     :listeners         (:listeners target)
                     :current-servers   (:servers census)
                     :current-listeners (:listeners census)})]
          (assoc r :committed target :census census)))
      (catch SetConfigurationException e
        {:cas-mismatch (.getMessage e), :census census})
      (catch Throwable t
        {:error (error-token t), :census census}))))

(defn- set-conf!
  "setConfiguration with the brief's mismatch-retry: attempt against the
  given census; on a CAS mismatch (someone else changed the conf since
  the census — legal), re-census once and retry once."
  [test matom census transform extra-route]
  (let [first-try (attempt-set-conf! census transform extra-route)]
    (if (:cas-mismatch first-try)
      (-> (attempt-set-conf! (census-conf test @matom) transform extra-route)
          (assoc :retried-after-mismatch (:cas-mismatch first-try)))
      first-try)))

(defn- reconcile-voters!
  "On a successful conf change, the committed server set IS the intended
  voter set (census-reconciled — belief drift dies here)."
  [matom result]
  (when (:success? result)
    (swap! matom assoc :voters (set (get-in result [:committed :servers]))))
  result)

(defn- bootstrap!
  "GroupManagementApi.add on `target` (routed via census servers +
  target): creates the empty-conf division that awaits the conf commit.
  AlreadyExistsException = an earlier attempt got there — fine."
  [census target]
  (let [route (vec (distinct (conj (:servers census) target)))]
    (try
      (with-open [^RaftClient c (client/open-raft-client
                                  (client/node-addresses route))]
        (client/group-add! c target))
      (catch Throwable t
        (if (already-exists? t)
          {:success? true, :already-bootstrapped? true}
          {:error (error-token t)})))))

(defn- pool-return!
  "Returns `node` to the clean pool posture: mark it :dynamic FIRST (so
  the db-layer start below picks --join), then kill -9, wipe the raft
  storage (the log survives — it is run evidence; on a durability run
  the wipe goes THROUGH the live lazyfs mount, contents only), and
  restart. On empty storage the node hosts nothing and awaits a future
  add."
  [test matom node]
  (swap! matom (fn [s] (-> s
                           (update :voters disj node)
                           (update :pool conj node)
                           (update :dynamic conj node))))
  (c/on-nodes test [node]
              (fn [t n]
                (jdb/kill! (:db t) t n)
                (db/wipe-storage! (boolean (:durability t)))
                (jdb/start! (:db t) t n))))

(defn- member-add!
  "The add move: pool node -> voter (up to max-voters)."
  [test matom]
  (let [{:keys [voters pool] :as state} @matom]
    (cond
      (>= (count voters) max-voters)
      {:move :add, :skip :voters-at-max, :voters (vec (sort voters))}

      (empty? pool)
      {:move :add, :skip :pool-empty}

      :else
      (let [target    (rand-nth (vec (sort pool)))
            census    (census-conf test state)
            bootstrap (bootstrap! census target)]
        (if-not (:success? bootstrap)
          {:move :add, :target target, :bootstrap bootstrap, :census census}
          (let [result (->> (set-conf! test matom census
                                       (fn [c]
                                         {:servers   (vec (distinct (conj (:servers c) target)))
                                          :listeners (:listeners c)})
                                       [target])
                            (reconcile-voters! matom))]
            (when (:success? result)
              (swap! matom update :pool disj target))
            {:move :add, :target target, :bootstrap bootstrap,
             :result result}))))))

(defn- member-remove!
  "The remove move: voter -> pool (down to the floor). The conf change
  commits first; only then is the node killed, storage-wiped and
  restarted in --join mode — the same decommission order the L3
  orchestrator would use. The removed-but-still-running window before
  the kill is deliberate stale-conf exposure (RATIS-2274 territory)."
  [test matom]
  (let [census  (census-conf test @matom)
        servers (:servers census)]
    (if (<= (count servers) min-voters)
      {:move :remove, :skip :at-floor, :census census}
      (let [victim (rand-nth (vec (sort servers)))
            result (->> (set-conf! test matom census
                                   (fn [c]
                                     {:servers   (vec (remove #{victim} (:servers c)))
                                      :listeners (:listeners c)})
                                   [])
                        (reconcile-voters! matom))
            returned (when (:success? result)
                       (try (pool-return! test matom victim)
                            (catch Exception e
                              (error-token e))))]
        {:move :remove, :victim victim, :result result,
         :pool-return returned}))))

(defn- member-replace-dead!
  "First half of the L3 rehearsal (the fault op): kill a voter, then
  commit a conf without it. Needs voters > min+? no — needs the conf to
  stay >= min-voters AFTER the remove, so voters must exceed min-voters;
  and a pool node must exist for the second half."
  [test matom stash]
  (let [{:keys [voters pool] :as state} @matom]
    (cond
      (<= (count voters) min-voters)
      {:move :replace-dead, :skip :at-floor, :voters (vec (sort voters))}

      (empty? pool)
      {:move :replace-dead, :skip :pool-empty}

      :else
      (let [victim (rand-nth (vec (sort voters)))
            _      (reset! stash victim)
            killed (try (c/on-nodes test [victim]
                                    (fn [t n] (jdb/kill! (:db t) t n)))
                        (catch Exception e (error-token e)))
            census (census-conf test state)
            result (->> (set-conf! test matom census
                                   (fn [c]
                                     {:servers   (vec (remove #{victim} (:servers c)))
                                      :listeners (:listeners c)})
                                   [])
                        (reconcile-voters! matom))]
        {:move :replace-dead, :victim victim, :killed killed,
         :remove-result result}))))

(defn- member-replace-done!
  "Second half (the heal op): make sure the dead victim is out of the
  conf (retrying the remove if the first half's commit failed), add a
  pool node in its place, then wipe+restart the corpse as pool. The
  restart always runs — the fault window must end with every node
  running, whatever the conf calls did."
  [test matom stash]
  (let [victim @stash]
    (if-not victim
      {:move :replace-done, :skip :nothing-to-heal}
      (let [_      (reset! stash nil)
            census (census-conf test @matom)
            remove-retry
            (when (some #{victim} (:servers census))
              (->> (set-conf! test matom census
                              (fn [c]
                                {:servers   (vec (remove #{victim} (:servers c)))
                                 :listeners (:listeners c)})
                              [])
                   (reconcile-voters! matom)))
            pool   (disj (:pool @matom) victim)
            add    (if (empty? pool)
                     {:skip :pool-empty}
                     (let [target    (rand-nth (vec (sort pool)))
                           census    (census-conf test @matom)
                           bootstrap (bootstrap! census target)]
                       (if-not (:success? bootstrap)
                         {:target target, :bootstrap bootstrap}
                         (let [result (->> (set-conf! test matom census
                                                      (fn [c]
                                                        {:servers   (vec (distinct (conj (:servers c) target)))
                                                         :listeners (:listeners c)})
                                                      [target])
                                           (reconcile-voters! matom))]
                           (when (:success? result)
                             (swap! matom update :pool disj target))
                           {:target target, :bootstrap bootstrap,
                            :result result}))))
            returned (try (pool-return! test matom victim)
                          (catch Exception e (error-token e)))]
        {:move :replace-done, :victim victim,
         :remove-retry remove-retry, :add add,
         :pool-return returned}))))

;; --- the listener-staging probe (bounded; RATIS-1825 territory) -----------

(defn- listener-add!
  "Stage a pool node as LISTENER: bootstrap its division, then a conf
  whose listeners list gains it (servers unchanged)."
  [test matom probe]
  (let [{:keys [pool] :as state} @matom]
    (if (empty? pool)
      {:probe :listener-add, :skip :pool-empty}
      (let [target    (or @probe (last (sort pool)))
            _         (reset! probe target)
            census    (census-conf test state)
            bootstrap (bootstrap! census target)]
        (if-not (:success? bootstrap)
          {:probe :listener-add, :target target, :bootstrap bootstrap}
          (let [result (set-conf! test matom census
                                  (fn [c]
                                    {:servers   (:servers c)
                                     :listeners (vec (distinct (conj (:listeners c) target)))})
                                  [target])]
            (when (:success? result)
              (swap! matom update :pool disj target))
            {:probe :listener-add, :target target, :bootstrap bootstrap,
             :result result}))))))

(defn- listener-census!
  "Replication census on the staged node: its last adopted conf line and
  conf-line count (conf entries are ordinary log entries — their arrival
  proves the leader replicates to it), the cluster's own census for
  comparison, and one linearizable read TARGETED at it. Whatever the
  read does — serves, refuses, wedges into the harness timeout — is the
  probe's signal and is recorded verbatim."
  [test matom probe]
  (let [target @probe]
    (if-not target
      {:probe :listener-census, :skip :no-probe-target}
      (let [node-view (try (-> (c/on-nodes test [target]
                                           (fn [_t _n]
                                             {:last-conf  (db/parse-conf-line
                                                            (db/last-conf-line!))
                                              :conf-lines (db/conf-line-count!)}))
                               (get target))
                           (catch Exception e (error-token e)))
            cluster   (census-conf test @matom)
            ;; no-retry client: the census wants the raw single-attempt
            ;; exception, not a retry-exhaustion wrapper
            read      (try
                        (with-open [^RaftClient c (client/open-probe-client
                                                    (client/node-addresses
                                                      (vec (distinct (conj (:servers cluster) target)))))]
                          {:reply (client/targeted-read! c target "GET 0")})
                        (catch Throwable t (error-token t)))]
        {:probe :listener-census, :target target,
         :node-view node-view, :cluster-census cluster,
         :targeted-read read}))))

(defn- listener-promote!
  "Listener -> voter: one conf that moves the staged node from the
  listeners list to the servers list — the exact conversion RATIS-1825
  left an open question."
  [test matom probe]
  (let [target @probe]
    (if-not target
      {:probe :listener-promote, :skip :no-probe-target}
      (let [census (census-conf test @matom)
            result (->> (set-conf! test matom census
                                   (fn [c]
                                     {:servers   (vec (distinct (conj (:servers c) target)))
                                      :listeners (vec (remove #{target} (:listeners c)))})
                                   [target])
                        (reconcile-voters! matom))]
        {:probe :listener-promote, :target target, :result result}))))

(defn- listener-demote!
  "Voter -> listener: the reverse conversion."
  [test matom probe]
  (let [target @probe]
    (if-not target
      {:probe :listener-demote, :skip :no-probe-target}
      (let [census (census-conf test @matom)
            result (->> (set-conf! test matom census
                                   (fn [c]
                                     {:servers   (vec (remove #{target} (:servers c)))
                                      :listeners (vec (distinct (conj (:listeners c) target)))})
                                   [target])
                        (reconcile-voters! matom))]
        {:probe :listener-demote, :target target, :result result}))))

(defn- listener-remove!
  "Drop the staged node from the listeners list and return it to the
  clean pool."
  [test matom probe]
  (let [target @probe]
    (if-not target
      {:probe :listener-remove, :skip :no-probe-target}
      (let [_      (reset! probe nil)
            census (census-conf test @matom)
            result (set-conf! test matom census
                              (fn [c]
                                {:servers   (vec (remove #{target} (:servers c)))
                                 :listeners (vec (remove #{target} (:listeners c)))})
                              [])
            returned (try (pool-return! test matom target)
                          (catch Exception e (error-token e)))]
        {:probe :listener-remove, :target target, :result result,
         :pool-return returned}))))

(defn membership-nemesis
  "The membership-churn nemesis and the listener probe, one reify: all
  their fs are routed here by full-nemesis. State lives in the test
  map's :membership-state atom (shared with db.clj and the targeting
  helpers); the replace-dead victim and the probe target ride
  nemesis-local atoms between a segment's ops (segments are atomic in
  every mode, so nothing interleaves)."
  []
  (let [stash (atom nil)   ; replace-dead victim awaiting its second half
        probe (atom nil)]  ; listener-probe target across the scripted ops
    (reify jn/Nemesis
      (setup! [this _test] this)

      (invoke! [_this test op]
        (let [matom (:membership-state test)]
          (assert matom "membership ops need :membership-state in the test map")
          (assoc op :value
                 (case (:f op)
                   :member-add          (member-add! test matom)
                   :member-remove       (member-remove! test matom)
                   :member-replace-dead (member-replace-dead! test matom stash)
                   :member-replace-done (member-replace-done! test matom stash)
                   :listener-add        (listener-add! test matom probe)
                   :listener-census     (listener-census! test matom probe)
                   :listener-promote    (listener-promote! test matom probe)
                   :listener-demote     (listener-demote! test matom probe)
                   :listener-remove     (listener-remove! test matom probe)))))

      (teardown! [_this _test]))))

(defn full-nemesis
  "One nemesis for all fault kinds, routing by :f. Present in every
  fault-bearing mode: the generator decides which faults actually fire,
  and unused members' setup/teardown are no-ops (the partitioner's heals
  the network, which is only ever protective)."
  []
  (jn/compose {#{:start :stop}    (jn/partition-random-halves)
               #{:crash :restart} (crash-nemesis)
               #{:pause :resume :quorum-pause :quorum-resume}
               (pause-nemesis)
               #{:churn-kill :churn-transfer :churn-snapshot :churn-restart}
               (churn-nemesis)
               #{:transfer}       (transfer-nemesis)
               #{:unsync-drop :unsync-restart :unsync-drop-all
                 :unsync-restart-all :torn-write :torn-restart}
               (durability-nemesis)
               #{:member-add :member-remove :member-replace-dead
                 :member-replace-done :listener-add :listener-census
                 :listener-promote :listener-demote :listener-remove}
               (membership-nemesis)}))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(defn segment
  "One self-contained fault cycle for `fault-f`: calm sleep, fault op,
  fault-window sleep, heal op. Target selection happens inside the
  nemesis at invocation time (it needs ssh for the leader census), so the
  emitted fault op carries no :value."
  [fault-f {:keys [calm-s fault-s]}]
  [(gen/sleep calm-s)
   {:type :info, :f fault-f}
   (gen/sleep fault-s)
   {:type :info, :f (fault->heal fault-f)}])

(defn partition-segment [cycles] (segment :start (:partition cycles)))
(defn crash-segment     [cycles] (segment :crash (:crash cycles)))
(defn pause-segment     [cycles] (segment :pause (:pause cycles)))

(defn quorum-pause-segment
  "One quorum stall (pinned cycle; Q14 lever)."
  [_cycles]
  (segment :quorum-pause quorum-pause-cycle))

(defn unsync-drop-segment
  "One minority power-loss cycle (pinned)."
  [_cycles]
  (segment :unsync-drop unsync-drop-cycle))

(defn unsync-drop-all-segment
  "One whole-cluster power-loss cycle (pinned)."
  [_cycles]
  (segment :unsync-drop-all unsync-drop-all-cycle))

(def torn-write-script
  "The bounded torn-write experiment (Job 11), scripted ONCE per run:
  calm writes, arm the tear on one follower's open segment, let it fire
  and sit dead through the window, then kill + remount + restart and
  record recovery or loud refusal. Finite on purpose — a refusal legally
  costs the node for the rest of the run, and repeating the script would
  walk the cluster below its majority (see the ns docstring). After the
  script the nemesis idles and the run continues calm."
  [(gen/sleep (:calm-s torn-write-cycle))
   {:type :info, :f :torn-write}
   (gen/sleep (:fault-s torn-write-cycle))
   {:type :info, :f :torn-restart}])

(defn churn-segment
  "One snapshot-churn cycle: calm; kill a follower; transfer leadership
  (term bump — closes every open log segment, see the ns docstring);
  let writes land in the new term; snapshot-and-purge every live
  server; restart the follower (on purge cycles install-snapshot is now
  its only recovery); the next segment's calm gives the install time to
  complete."
  [cycles]
  (let [{:keys [calm-s kill-to-transfer-s transfer-to-snapshot-s
                snapshot-to-restart-s]}
        (:churn cycles)]
    [(gen/sleep calm-s)
     {:type :info, :f :churn-kill}
     (gen/sleep kill-to-transfer-s)
     {:type :info, :f :churn-transfer}
     (gen/sleep transfer-to-snapshot-s)
     {:type :info, :f :churn-snapshot}
     (gen/sleep snapshot-to-restart-s)
     {:type :info, :f :churn-restart}]))

(defn transfer-segment
  "One leadership-transfer cycle: calm, then one transfer attempt."
  [cycles]
  [(gen/sleep (get-in cycles [:transfer :calm-s]))
   {:type :info, :f :transfer}])

(defn member-add-segment
  "One membership cycle: calm, then one add move."
  [cycles]
  [(gen/sleep (get-in cycles [:membership :calm-s]))
   {:type :info, :f :member-add}])

(defn member-remove-segment
  "One membership cycle: calm, then one remove move."
  [cycles]
  [(gen/sleep (get-in cycles [:membership :calm-s]))
   {:type :info, :f :member-remove}])

(defn member-replace-segment
  "One replace-dead cycle (the L3 rehearsal): calm; kill a voter and
  commit it out of the conf; leave the cluster in the shrunken conf for
  replace-dead-s; then add a pool node and resurrect the corpse as pool."
  [cycles]
  [(gen/sleep (get-in cycles [:membership :calm-s]))
   {:type :info, :f :member-replace-dead}
   (gen/sleep (get-in cycles [:membership :replace-dead-s]))
   {:type :info, :f :member-replace-done}])

(defn membership-segment
  "One membership cycle with the move drawn at random: add | remove |
  replace-dead. Legality is the nemesis's problem at invocation time —
  an illegal draw (band edge, empty pool) records a skip and the run
  moves on. Used by mixed-all, where membership is one fault kind among
  six and any single move is fine; the DEDICATED membership generators
  use membership-move-blocks instead — see there for why."
  [cycles]
  ((rand-nth [member-add-segment member-remove-segment
              member-replace-segment])
   cycles))

(defn membership-move-blocks
  "An endless stream of membership segment-fns in which every
  consecutive block of three contains add, remove and replace-dead
  exactly once, in shuffled order. Guaranteed per-run coverage by
  construction: a plain uniform draw can starve a move for an entire
  300 s run (observed live — the first membership shakedown drew 15
  segments with zero adds, ratcheting the conf to the floor), and both
  the acceptance gates and the combined kind's joiner-install evidence
  NEED committed adds. A block also nets a voter change of zero
  (+1 −1 ±0), which keeps the count oscillating inside the 5±2 band
  instead of drifting."
  []
  (mapcat shuffle (repeat [member-add-segment member-remove-segment
                           member-replace-segment])))

(def listener-probe-script
  "The bounded listener-staging probe (brief deliverable 4), scripted
  once: stage the pool node as LISTENER, give replication 15 s, census
  it, promote it to voter, census again, demote it back, remove it. All
  actions — no fault windows — and finite: after the script the nemesis
  idles and the run continues calm."
  [(gen/sleep 15) {:type :info, :f :listener-add}
   (gen/sleep 15) {:type :info, :f :listener-census}
   (gen/sleep 3)  {:type :info, :f :listener-promote}
   (gen/sleep 12) {:type :info, :f :listener-census}
   (gen/sleep 3)  {:type :info, :f :listener-demote}
   (gen/sleep 12) {:type :info, :f :listener-remove}])

(defn- interleave-generator
  "An infinite random concatenation of whole segments drawn uniformly
  from `segment-fns` — the roughly-equal-weight interleave. Because
  segments are atomic, faults never overlap and every fault is healed
  before the next begins."
  [cycles segment-fns]
  (apply concat (repeatedly #((rand-nth segment-fns) cycles))))

(defn mixed-generator
  "The M1 mix, unchanged: partition | crash | pause."
  [cycles]
  (interleave-generator cycles
                        [partition-segment crash-segment pause-segment]))

(defn membership-generator
  "An endless stream of membership segments drawn from
  membership-move-blocks: order random, every block of three covers all
  three moves."
  [cycles]
  (mapcat #(% cycles) (membership-move-blocks)))

(defn membership-churn-generator
  "The combined kind (brief deliverable 3's second half): snapshot-churn
  and membership segments in strict alternation, CHURN FIRST — the
  opening churn cycle forces a snapshot before the first join, and a
  bootstrapping (staged) follower is sent a real install whenever the
  leader holds any snapshot (LogAppender.shouldInstallSnapshot rule 3
  at 3.2.2), so joins land on the
  bootstrap-catch-up-via-install-snapshot path from the first add
  onward. Membership moves come from membership-move-blocks (guaranteed
  add coverage — the joiner-install evidence needs committed adds)."
  [cycles]
  (mapcat (fn [segment-fn] (concat (churn-segment cycles)
                                   (segment-fn cycles)))
          (membership-move-blocks)))

(defn mixed-all-generator
  "Everything the harness has: the M1 three plus snapshot churn,
  leadership transfer and membership churn, uniform per segment."
  [cycles]
  (interleave-generator cycles
                        [partition-segment crash-segment pause-segment
                         churn-segment transfer-segment
                         membership-segment]))

(def membership-kinds
  "The fault schedules that involve dynamic membership: these need the
  full 7-node topology (the dormant pool) and the :membership-state atom
  in the test map — core.clj keys both off this set."
  #{"membership" "membership-snapshot-churn" "listener-probe" "mixed-all"})

(defn package
  "Returns {:nemesis n, :generator g} for a fault-schedule name and the
  parsed CLI options (cycle overrides). The generator runs on jepsen's
  nemesis thread; nil means the nemesis sits idle."
  ([kind] (package kind {}))
  ([kind opts]
   (let [cs (cycles opts)]
     (case kind
       "none"           {:nemesis   jn/noop
                         :generator nil}
       "partition"      {:nemesis   (full-nemesis)
                         :generator (cycle (partition-segment cs))}
       "crash"          {:nemesis   (full-nemesis)
                         :generator (cycle (crash-segment cs))}
       "pause"          {:nemesis   (full-nemesis)
                         :generator (cycle (pause-segment cs))}
       "mixed"          {:nemesis   (full-nemesis)
                         :generator (mixed-generator cs)}
       "snapshot-churn" {:nemesis   (full-nemesis)
                         :generator (cycle (churn-segment cs))}
       "transfer"       {:nemesis   (full-nemesis)
                         :generator (cycle (transfer-segment cs))}
       "membership"     {:nemesis   (full-nemesis)
                         :generator (membership-generator cs)}
       "membership-snapshot-churn"
       {:nemesis   (full-nemesis)
        :generator (membership-churn-generator cs)}
       "listener-probe" {:nemesis   (full-nemesis)
                         :generator listener-probe-script}
       "quorum-pause"   {:nemesis   (full-nemesis)
                         :generator (cycle (quorum-pause-segment cs))}
       "unsync-drop"    {:nemesis   (full-nemesis)
                         :generator (cycle (unsync-drop-segment cs))}
       "unsync-drop-all" {:nemesis   (full-nemesis)
                          :generator (cycle (unsync-drop-all-segment cs))}
       "torn-write"     {:nemesis   (full-nemesis)
                         :generator torn-write-script}
       "mixed-all"      {:nemesis   (full-nemesis)
                         :generator (mixed-all-generator cs)}))))
