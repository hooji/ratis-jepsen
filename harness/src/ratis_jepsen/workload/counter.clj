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

(ns ratis-jepsen.workload.counter
  "The M3 exactly-once increment workload (PLAN M3/Q14): known-delta
  :add ops mixed with :reads over independent keys, driven through the
  standard bounded same-callId retry client — the server retry cache
  ((ClientId, callId) dedup, rebuilt at apply on every replica) is the
  test subject. An :ok add must count EXACTLY once; an :info add 0-or-1
  times; a :fail add exactly zero (the outcome map's definite-fail
  guarantee). Any double-count or loss is a conviction.

  Generator shape per key: adds (deltas 1..5, recorded in the op) and
  reads mixed 3:1 under the shared rate/budget knobs, then a quiesce
  sleep and three FINAL reads — the final reads make the generic
  read-bounds rule (ratis-jepsen.checker/counter) equivalent to
  final-value exactness: their lower bound is the exact :ok sum, their
  upper the :ok sum plus the 0-or-1 allowance.

  The dedup evidence law (brief deliverable 4): fault-bearing counter
  runs REQUIRE nonzero client retry activity (:retries on completions,
  counted by the client's observing retry policy) — a dedup run that
  never retried tested nothing and fails with :no-retry-evidence."
  (:require [clojure.java.shell :as shell]
            [clojure.tools.logging :as log]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.generator :as gen]
            [jepsen.independent :as independent]
            [ratis-jepsen.checker :as rj-checker]
            [ratis-jepsen.workload.register :as register]))

(def max-delta
  "Deltas are uniform 1..max-delta: positive (totals strictly increase,
  which the duplicate-observed-total assertion relies on) and known
  per-op (recorded in :value; the checker sums them)."
  5)

(def final-reads
  "How many final reads each key issues after the quiesce sleep — more
  than one so a read landing in a fault window still leaves a usable
  final observation."
  3)

(def settle-s
  "Quiesce sleep between the main mix and the final reads: long enough
  for in-flight adds to complete or hit their deadline."
  5)

(defn add [_ _] {:type :invoke, :f :add, :value (inc (rand-int max-delta))})
(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn final-read [_ _] {:type :invoke, :f :read, :value nil, :final? true})

(defn generator
  "key-count independent keys in parallel (register's threads-per-key
  split); per key: a 3:1 add/read mix at ~:rate ops/s per worker capped
  at (ops-per-key − final-reads), then the quiesce and the final reads."
  [{:keys [key-count ops-per-key rate] :as opts}]
  (independent/concurrent-generator
    (register/threads-per-key opts)
    (range key-count)
    (fn [_k]
      [(->> (gen/mix [add add add r])
            (gen/stagger (/ 1.0 (or rate 10)))
            (gen/limit (max 1 (- ops-per-key final-reads))))
       (gen/sleep settle-s)
       (->> (repeat final-reads (final-read nil nil))
            (gen/stagger 1))])))

(defn- gnuplot-available?
  "Same probe as the register workload: perf plots only where gnuplot
  exists."
  []
  (try (zero? (:exit (shell/sh "gnuplot" "--version")))
       (catch Exception _ false)))

(defn workload
  "The counter workload: {:generator, :checker}. Retry evidence is
  REQUIRED for fault-bearing runs (--nemesis anything but none): those
  are the dedup runs; a calm counter run has nothing to retry and owes
  nothing."
  [opts]
  (let [gnuplot? (gnuplot-available?)]
    (when-not gnuplot?
      (log/warn "gnuplot not found — perf plots disabled for this run"))
    {:generator (generator opts)
     :checker   (checker/compose
                  (cond-> {:independent (independent/checker
                                          (checker/compose
                                            {:counter  (rj-checker/counter)
                                             :timeline (timeline/html)}))
                           :liveness   (rj-checker/liveness)
                           :retry-evidence
                           (rj-checker/retry-evidence
                             {:require-evidence?
                              (not= "none" (:nemesis opts))})
                           ;; Job 11: durability × exactly-once — a
                           ;; counter run under a durability kind owes
                           ;; the same fault evidence as the register
                           ;; workload.
                           :durability-evidence
                           (rj-checker/durability-evidence
                             {:require-evidence?
                              (contains? #{"unsync-drop" "unsync-drop-all"
                                           "torn-write"}
                                         (:nemesis opts))})
                           :stats      (checker/stats)
                           :exceptions (checker/unhandled-exceptions)}
                    gnuplot? (assoc :perf (checker/perf))))}))
