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

  Job 03 stage: the real db + client + outcome map wired into a runnable
  jepsen test with no workload (:generator nil) and a noop nemesis —
  Job 04 brings the register workload, generators, checkers and the
  partition nemesis. `clojure -M:run test --help` shows usage."
  (:require [jepsen.cli :as cli]
            [jepsen.nemesis :as nemesis]
            [jepsen.tests :as tests]
            [ratis-jepsen.client :as client]
            [ratis-jepsen.db :as db]))

(defn ratis-test
  "Builds the test map from parsed CLI options. Note jepsen's default
  --nodes (n1..n5) equals the deployment contract's initial voters; the
  db and client construct the raft group from the contract regardless."
  [opts]
  (merge tests/noop-test
         opts
         {:name      "ratis-kv"
          :db        (db/db)
          :client    (client/client)
          :nemesis   nemesis/noop
          ;; Workload stub — Job 04 owns generators and checkers.
          :generator nil}))

(defn -main
  [& args]
  ;; :usage passed as a string — jepsen 0.3.13's default hands the
  ;; test-usage fn itself to the printer, which renders as #object[...].
  (cli/run! (cli/single-test-cmd {:test-fn ratis-test
                                  :usage   (cli/test-usage)})
            args))
