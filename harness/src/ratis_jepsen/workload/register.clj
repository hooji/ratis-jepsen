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

(ns ratis-jepsen.workload.register
  "The M0 register workload (DESIGN 2.5): reads, writes and CAS roughly
  equally mixed over jepsen.independent keys.

  Budget discipline: the per-key generator is hard-capped with (gen/limit
  ops-per-key ...) — the knossos cost cliff is bounded in code, not prose.
  Worker groups of `threads-per-key` walk the finite key sequence one key
  at a time, ~10 ops/s per worker (gen/stagger 1/10), so a default run
  (5 keys x 400 ops, concurrency 10) generates at most 2000 ops total.

  The checker: per-key knossos linearizability against a cas-register
  model (+ per-key timeline), composed with the whole-history liveness
  checker (M1 — a healthy-majority cluster that stops acking flags the
  run), perf, stats and unhandled-exceptions."
  (:require [clojure.java.shell :as shell]
            [clojure.tools.logging :as log]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.generator :as gen]
            [jepsen.independent :as independent]
            [knossos.model :as model]
            [ratis-jepsen.checker :as rj-checker]))

(def value-range
  "Written/CAS'd values are small ints — keeps CAS preconditions hitting
  often enough that every :f sees successes in short runs."
  5)

(defn r   [_ _] {:type :invoke, :f :read,  :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int value-range)})
(defn cas [_ _] {:type :invoke, :f :cas,   :value [(rand-int value-range)
                                                   (rand-int value-range)]})

(defn threads-per-key
  "Worker threads per key: total concurrency spread across all keys, so
  the keys run in parallel (concurrency 10 over 5 keys = 2 workers/key).
  Keeping per-key concurrency low is what keeps knossos bounded: every
  op a partition window crashes stays concurrent-forever in that key's
  history, and the linear checker is exponential in those — piling all
  ten workers onto one key at a time OOMed a reference run's analysis."
  [{:keys [concurrency key-count]}]
  (max 1 (quot concurrency key-count)))

(defn generator
  "The client-op generator: key-count independent keys checked in
  parallel by groups of threads-per-key workers, each key's history
  hard-capped at ops-per-key ops, mixed r/w/cas at ~:rate ops/s per
  worker (default 10 — the M0 behavior; snapshot-churn runs slow this
  down so the write stream outlives the purge-gap milestones instead of
  exhausting the op budget in the first seconds)."
  [{:keys [key-count ops-per-key rate] :as opts}]
  (independent/concurrent-generator
    (threads-per-key opts)
    (range key-count)
    (fn [_k]
      (->> (gen/mix [r w cas])
           (gen/stagger (/ 1.0 (or rate 10)))
           ;; THE hard cap (DESIGN 6: "hard-cap in the generator, not in
           ;; prose") — bounds each per-key history knossos must check.
           (gen/limit ops-per-key)))))

(defn- gnuplot-available?
  "jepsen's perf checker shells out to gnuplot and errors the whole
  analysis to :unknown when the binary is missing outright; probe first."
  []
  (try (zero? (:exit (shell/sh "gnuplot" "--version")))
       (catch Exception _ false)))

(defn workload
  "The register workload: {:generator, :checker} for the given parsed CLI
  options (:key-count, :ops-per-key). The perf checker joins the
  composition only where gnuplot exists — missing plots must degrade to a
  loud warning, not grade a valid run :unknown."
  [opts]
  (let [gnuplot? (gnuplot-available?)]
    (when-not gnuplot?
      (log/warn "gnuplot not found — perf plots disabled for this run"))
    {:generator (generator opts)
     :checker   (checker/compose
                  (cond-> {:independent (independent/checker
                                          (checker/compose
                                            {:linearizable
                                             (checker/linearizable
                                               {:model     (model/cas-register)
                                                :algorithm :linear})
                                             :timeline (timeline/html)}))
                           :liveness   (rj-checker/liveness)
                           ;; Counts always reported; REQUIRED (zero ⇒
                           ;; invalid) only for dedicated snapshot-churn
                           ;; runs, whose write stream is sized to cross
                           ;; the purge-gap milestones.
                           :install-snapshot-evidence
                           (rj-checker/install-snapshot-evidence
                             {:require-evidence?
                              (= "snapshot-churn" (:nemesis opts))})
                           ;; Job 08's law: a dedicated membership run
                           ;; must prove committed conf changes; the
                           ;; combined kind must additionally show
                           ;; install-snapshot on a node that joined
                           ;; during the run. The listener probe and
                           ;; mixed-all report counts without requiring
                           ;; them (a probe wedge is a reportable
                           ;; outcome, and mixed-all's membership share
                           ;; is not guaranteed a committed move).
                           :membership-evidence
                           (rj-checker/membership-evidence
                             {:require-evidence?
                              (contains? #{"membership"
                                           "membership-snapshot-churn"}
                                         (:nemesis opts))
                              :min-changes
                              (:membership-min-conf-changes opts 2)})
                           :joiner-install-evidence
                           (rj-checker/joiner-install-evidence
                             {:require-evidence?
                              (= "membership-snapshot-churn"
                                 (:nemesis opts))})
                           ;; Job 11's law: a dedicated durability run
                           ;; must prove its lazyfs faults happened
                           ;; (clear-cache acks / a fired tear plus a
                           ;; re-proven remount).
                           :durability-evidence
                           (rj-checker/durability-evidence
                             {:require-evidence?
                              (contains? #{"unsync-drop" "unsync-drop-all"
                                           "torn-write"}
                                         (:nemesis opts))})
                           :stats      (checker/stats)
                           :exceptions (checker/unhandled-exceptions)}
                    gnuplot? (assoc :perf (checker/perf))))}))
