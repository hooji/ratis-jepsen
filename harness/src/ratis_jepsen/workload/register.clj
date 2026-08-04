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
  model (+ per-key timeline), composed with whole-history perf, stats and
  unhandled-exceptions."
  (:require [clojure.java.shell :as shell]
            [clojure.tools.logging :as log]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.generator :as gen]
            [jepsen.independent :as independent]
            [knossos.model :as model]))

(def value-range
  "Written/CAS'd values are small ints — keeps CAS preconditions hitting
  often enough that every :f sees successes in short runs."
  5)

(defn r   [_ _] {:type :invoke, :f :read,  :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int value-range)})
(defn cas [_ _] {:type :invoke, :f :cas,   :value [(rand-int value-range)
                                                   (rand-int value-range)]})

(def threads-per-key
  "Worker threads concurrently hammering one key (DESIGN 2.5 budget:
  concurrency 10, all of it on the active key)."
  10)

(defn generator
  "The client-op generator: groups of threads-per-key workers walk
  key-count independent keys, each key's history hard-capped at
  ops-per-key ops, mixed r/w/cas at ~10 ops/s per worker."
  [{:keys [key-count ops-per-key]}]
  (independent/concurrent-generator
    threads-per-key
    (range key-count)
    (fn [_k]
      (->> (gen/mix [r w cas])
           (gen/stagger 1/10)
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
                           :stats      (checker/stats)
                           :exceptions (checker/unhandled-exceptions)}
                    gnuplot? (assoc :perf (checker/perf))))}))
