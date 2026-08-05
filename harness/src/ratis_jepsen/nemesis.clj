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
  "Fault schedules (Job 05 M1 + Job 07 M2 part 1):
  none | partition | crash | pause | mixed | snapshot-churn | transfer |
  mixed-all.

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

  Each kind's schedule is a self-contained *segment* — calm sleep, then
  fault/action ops, ending healed — so a run is a concatenation of
  segments that always starts calm and always heals one fault before the
  next begins (in mixed modes a pause can therefore never land on a node
  a previous crash left dead). `mixed` draws uniformly from the three M1
  kinds (unchanged); `mixed-all` draws uniformly from all five.

  Fault windows for the liveness checker (ratis-jepsen.checker) are
  derived from fault->heal: a window opens at a fault op's invocation and
  closes at its heal op's completion. Action fs (:churn-snapshot,
  :transfer) appear in histories but never gate liveness — a transfer
  blip is far inside the checker's 60 s window."
  (:require [clojure.tools.logging :as log]
            [jepsen.control :as c]
            [jepsen.db :as jdb]
            [jepsen.generator :as gen]
            [jepsen.nemesis :as jn]
            [ratis-jepsen.client :as client]
            [ratis-jepsen.db :as db])
  (:import (org.apache.ratis.client RaftClient)))

(def kinds
  "CLI-selectable fault schedules."
  #{"none" "partition" "crash" "pause" "mixed"
    "snapshot-churn" "transfer" "mixed-all"})

(def fault->heal
  "Each fault-opening op :f and the op :f that heals it. The single
  source of truth for the nemesis vocabulary; the liveness checker's
  nemesis-aware gating is built from this map."
  {:start      :stop
   :crash      :restart
   :pause      :resume
   :churn-kill :churn-restart})

(def fault-fs (set (keys fault->heal)))
(def heal-fs  (set (vals fault->heal)))

(def action-fs
  "Nemesis ops that act on the cluster without opening a fault window:
  they never enter fault->heal and the liveness checker ignores them."
  #{:churn-transfer :churn-snapshot :transfer})

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
                        (:calm-s default-transfer-cycle))}})

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
  "The nodes a :crash op kills: a random minority of the test's nodes,
  forced to include the current leader with probability leader-bias.
  The leader census is best-effort (db/current-leaders! greps logs over
  ssh); any failure or empty census degrades to unbiased selection."
  [test]
  (let [nodes  (vec (:nodes test))
        leader (when (< (rand) leader-bias)
                 (try (let [ls (db/current-leaders! test)]
                        (when (seq ls) (rand-nth ls)))
                      (catch Exception e
                        (log/warn "leader census failed — unbiased crash"
                                  "targets this cycle:" (.getMessage e))
                        nil)))]
    (select-targets nodes leader (target-count (count nodes)))))

(defn pause-targets!
  "The nodes a :pause op stops: a random minority, unbiased."
  [test]
  (let [nodes (vec (:nodes test))]
    (select-targets nodes nil (target-count (count nodes)))))

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
  SIGCONT on every node. Teardown resumes everything so a run cut mid-
  pause still tears down (and flushes logs) from running processes."
  []
  (reify jn/Nemesis
    (setup! [this _test] this)

    (invoke! [_this test op]
      (case (:f op)
        :pause  (let [targets (pause-targets! test)]
                  (assoc op :value
                         (c/on-nodes test targets
                                     (fn [t node] (jdb/pause! (:db t) t node)))))
        :resume (assoc op :value
                       (c/on-nodes test
                                   (fn [t node] (jdb/resume! (:db t) t node))))))

    (teardown! [_this test]
      (try (c/on-nodes test (fn [t node] (jdb/resume! (:db t) t node)))
           (catch Exception e
             (log/warn "pause-nemesis teardown resume failed:"
                       (.getMessage e)))))))

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
                        (client/node-addresses (:nodes test))))
        this)

      (invoke! [_this test op]
        (case (:f op)
          :churn-kill
          (let [nodes    (vec (:nodes test))
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
                leaders (set (concat (try (db/current-leaders! test)
                                          (catch Exception e
                                            (log/warn "churn-transfer census"
                                                      "failed:" (.getMessage e))
                                            []))
                                     (some-> (.getLeaderId c) str vector)))
                cands  (or (seq (remove (conj leaders @killed) (:nodes test)))
                           (remove #{@killed} (:nodes test)))
                target (rand-nth (vec cands))]
            (assoc op :value
                   {:target target
                    :result (try (client/transfer-leadership! c target)
                                 (catch Throwable t
                                   [:error (.getName (class t))
                                    (.getMessage t)]))}))

          :churn-snapshot
          (let [live (remove #{@killed} (:nodes test))]
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
                        (client/node-addresses (:nodes test))))
        this)

      (invoke! [_this test op]
        (case (:f op)
          :transfer
          (let [target (rand-nth (vec (:nodes test)))]
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

(defn full-nemesis
  "One nemesis for all fault kinds, routing by :f. Present in every
  fault-bearing mode: the generator decides which faults actually fire,
  and unused members' setup/teardown are no-ops (the partitioner's heals
  the network, which is only ever protective)."
  []
  (jn/compose {#{:start :stop}    (jn/partition-random-halves)
               #{:crash :restart} (crash-nemesis)
               #{:pause :resume}  (pause-nemesis)
               #{:churn-kill :churn-transfer :churn-snapshot :churn-restart}
               (churn-nemesis)
               #{:transfer}       (transfer-nemesis)}))

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

(defn mixed-all-generator
  "Everything the harness has: the M1 three plus snapshot churn and
  leadership transfer, uniform per segment."
  [cycles]
  (interleave-generator cycles
                        [partition-segment crash-segment pause-segment
                         churn-segment transfer-segment]))

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
       "mixed-all"      {:nemesis   (full-nemesis)
                         :generator (mixed-all-generator cs)}))))
