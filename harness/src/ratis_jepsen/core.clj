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

(ns ratis-jepsen.core
  "CLI entry and test-map assembly (DESIGN 2.1).

  M1 shape: the register workload over independent keys, a fault schedule
  chosen by --nemesis, knossos linearizability checking per key plus the
  whole-history liveness checker. `clojure -M:run test --help` for the
  options; the interesting ones:

    --workload register        (the only workload until M2/M3)
    --nemesis none|partition|crash|pause|mixed
                               fault schedule (default none); crash =
                               kill -9/restart of a leader-biased random
                               minority, pause = SIGSTOP/SIGCONT, mixed
                               interleaves all three at equal weight
    --time-limit 300           wall-clock ceiling; the op budget usually
                               exhausts first
    --key-count 5 --ops-per-key 400 --concurrency 10   the DESIGN 2.5
                               knossos budget
    --seed-bug stale-reads     start every node with the SUT's seeded bug
                               (the red-run lever; testing the harness)
    --store-dir DIR            where jepsen's store/ output lands"
  (:require [jepsen.cli :as cli]
            [jepsen.generator :as gen]
            [jepsen.store :as store]
            [jepsen.tests :as tests]
            [ratis-jepsen.client :as client]
            [ratis-jepsen.db :as db]
            [ratis-jepsen.nemesis :as nemesis]
            [ratis-jepsen.workload.register :as register]))

(def workloads
  "Workload name -> (fn [opts] {:generator, :checker}). M3 adds the
  increment workload here."
  {"register" register/workload})

(def cli-opts
  "Additional CLI options, merged over jepsen's test-opt-spec (same
  option string = replaces jepsen's entry, which is how --concurrency
  and --time-limit get M0-budget defaults)."
  [[nil "--workload NAME" "Workload to run"
    :default "register"
    :validate [workloads (cli/one-of workloads)]]

   [nil "--nemesis NAME" "Fault schedule during the run"
    :default "none"
    :validate [nemesis/kinds (cli/one-of nemesis/kinds)]]

   ;; Crash/pause cycle knobs (the brief's "configurable cycle"); defaults
   ;; live in ratis-jepsen.nemesis. The partition cycle is pinned (Job 04).
   [nil "--crash-calm-s SECONDS" "Crash cycle: calm stretch before each kill"
    :default (:calm-s nemesis/default-crash-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--crash-fault-s SECONDS" "Crash cycle: how long nodes stay killed"
    :default (:fault-s nemesis/default-crash-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--pause-calm-s SECONDS" "Pause cycle: running stretch before each SIGSTOP"
    :default (:calm-s nemesis/default-pause-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--pause-fault-s SECONDS" "Pause cycle: how long processes stay stopped"
    :default (:fault-s nemesis/default-pause-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--key-count NUMBER" "How many independent register keys to run through"
    :default 5
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   ;; Default shrunk from the brief's 400 after a reference run's key
   ;; history (400 ops with ~40 partition-:info) blew knossos's memory —
   ;; the sanctioned lever ("if analysis exceeds the budget, shrink
   ;; ops-per-key and say so"); DESIGN 2.5 pins only <=400.
   [nil "--ops-per-key NUMBER" "Hard cap on ops per key (the knossos budget)"
    :default 300
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--seed-bug MODE" "Start every node with a deliberately seeded SUT bug (stale-reads). Testing the harness only — a run with this flag is expected to FAIL its checker."
    :validate [#{"stale-reads"} "Must be: stale-reads"]]

   [nil "--store-dir DIR" "Directory jepsen writes its store/ results under"
    :default "store"]

   ;; Overrides of jepsen defaults for the DESIGN 2.5 budget; option
   ;; strings must match jepsen's exactly for merge-opt-specs to replace.
   [nil "--concurrency NUMBER" "How many workers should we run? Must be an integer, optionally followed by n (e.g. 3n) to multiply by the number of nodes."
    :default "10"
    :validate [(partial re-find #"^\d+n?$")
               "Must be an integer, optionally followed by n."]]

   [nil "--time-limit SECONDS"
    "Excluding setup and teardown, how long should a test run for, in seconds?"
    :default 300
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]])

(defn ratis-test
  "Builds the test map from parsed CLI options: register workload +
  chosen nemesis over the db/client/outcome stack from Job 03."
  [opts]
  ;; jepsen.store writes under its base-dir var; point it wherever the
  ;; caller asked (env/run.sh test passes /ratis-jepsen/store so results
  ;; land on the bind mount outside the container).
  (alter-var-root #'store/base-dir (constantly (:store-dir opts)))
  (let [workload ((workloads (:workload opts)) opts)
        nem      (nemesis/package (:nemesis opts) opts)]
    (merge tests/noop-test
           opts
           {:name      (str "ratis-kv-" (:workload opts)
                            "-" (:nemesis opts)
                            (when (:seed-bug opts)
                              (str "-seedbug-" (:seed-bug opts))))
            :db        (db/db (:seed-bug opts))
            :client    (client/client)
            :nemesis   (:nemesis nem)
            :checker   (:checker workload)
            :generator (->> (:generator workload)
                            (gen/nemesis (:generator nem))
                            (gen/time-limit (:time-limit opts)))})))

(defn -main
  [& args]
  ;; :usage passed as a string — jepsen 0.3.13's default hands the
  ;; test-usage fn itself to the printer, which renders as #object[...].
  (cli/run! (cli/single-test-cmd {:test-fn  ratis-test
                                  :opt-spec cli-opts
                                  :usage    (cli/test-usage)})
            args))
