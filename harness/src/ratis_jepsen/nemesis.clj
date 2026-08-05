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
  "M1 fault schedules (Job 05): none | partition | crash | pause | mixed.

  Vocabulary — every nemesis op :f is unique across fault kinds, so one
  composed nemesis routes ops in every mode and history event names never
  depend on which --nemesis was chosen:

    :start / :stop      random-halves network partition (Job 04, unchanged)
    :crash / :restart   kill -9 a random minority (leader-biased), restart
    :pause / :resume    SIGSTOP a random minority, SIGCONT

  Each kind's schedule is a self-contained *segment* — calm sleep, fault
  op, fault-window sleep, heal op — so a run is a concatenation of
  segments that always starts calm and always heals one fault before the
  next begins (in mixed mode a pause can therefore never land on a node
  the previous crash left dead). `mixed` draws segments uniformly at
  random from the three kinds.

  Fault windows for the liveness checker (ratis-jepsen.checker) are
  derived from these fs via fault->heal: a window opens at a fault op's
  invocation and closes at its heal op's completion."
  (:require [clojure.tools.logging :as log]
            [jepsen.control :as c]
            [jepsen.db :as jdb]
            [jepsen.generator :as gen]
            [jepsen.nemesis :as jn]
            [ratis-jepsen.db :as db]))

(def kinds
  "CLI-selectable fault schedules."
  #{"none" "partition" "crash" "pause" "mixed"})

(def fault->heal
  "Each fault-opening op :f and the op :f that heals it. The single
  source of truth for the nemesis vocabulary; the liveness checker's
  nemesis-aware gating is built from this map."
  {:start :stop
   :crash :restart
   :pause :resume})

(def fault-fs (set (keys fault->heal)))
(def heal-fs  (set (vals fault->heal)))

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

(defn cycles
  "The three cycles for a run: partition pinned, crash/pause from the
  parsed CLI options (:crash-calm-s, :crash-fault-s, :pause-calm-s,
  :pause-fault-s) where present, brief defaults where not."
  [opts]
  {:partition partition-cycle
   :crash     {:calm-s  (:crash-calm-s opts (:calm-s default-crash-cycle))
               :fault-s (:crash-fault-s opts (:fault-s default-crash-cycle))}
   :pause     {:calm-s  (:pause-calm-s opts (:calm-s default-pause-cycle))
               :fault-s (:pause-fault-s opts (:fault-s default-pause-cycle))}})

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

(defn full-nemesis
  "One nemesis for all fault kinds, routing by :f. Present in every
  fault-bearing mode: the generator decides which faults actually fire,
  and unused members' setup/teardown are no-ops (the partitioner's heals
  the network, which is only ever protective)."
  []
  (jn/compose {#{:start :stop}    (jn/partition-random-halves)
               #{:crash :restart} (crash-nemesis)
               #{:pause :resume}  (pause-nemesis)}))

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

(defn mixed-generator
  "An infinite random concatenation of whole segments, drawn uniformly
  from the three fault kinds — the roughly-equal-weight interleave.
  Because segments are atomic, faults never overlap and every fault is
  healed before the next begins."
  [cycles]
  (apply concat
         (repeatedly #(rand-nth [(partition-segment cycles)
                                 (crash-segment cycles)
                                 (pause-segment cycles)]))))

(defn package
  "Returns {:nemesis n, :generator g} for a fault-schedule name and the
  parsed CLI options (crash/pause cycle overrides). The generator runs on
  jepsen's nemesis thread; nil means the nemesis sits idle."
  ([kind] (package kind {}))
  ([kind opts]
   (let [cs (cycles opts)]
     (case kind
       "none"      {:nemesis   jn/noop
                    :generator nil}
       "partition" {:nemesis   (full-nemesis)
                    :generator (cycle (partition-segment cs))}
       "crash"     {:nemesis   (full-nemesis)
                    :generator (cycle (crash-segment cs))}
       "pause"     {:nemesis   (full-nemesis)
                    :generator (cycle (pause-segment cs))}
       "mixed"     {:nemesis   (full-nemesis)
                    :generator (mixed-generator cs)}))))
