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
  "M0 nemesis wiring: none, or a random-halves network partition on a
  15 s on / 15 s off cycle (DESIGN 2.5). This namespace is deliberately a
  thin seam — M2 grows it (membership churn, snapshot churn, leadership
  transfer) without the CLI shape changing."
  (:require [jepsen.generator :as gen]
            [jepsen.nemesis :as jn]))

(def kinds
  "CLI-selectable fault schedules."
  #{"none" "partition"})

(def cycle-seconds
  "Partition cycle half-period: 15 s healed, 15 s partitioned (DESIGN 2.5)."
  15)

(defn package
  "Returns {:nemesis n, :generator g} for a fault-schedule name. The
  generator runs on jepsen's nemesis thread; nil means the nemesis sits
  idle. Cycles start healed so runs open with a calm window."
  [kind]
  (case kind
    "none"      {:nemesis   jn/noop
                 :generator nil}
    "partition" {:nemesis   (jn/partition-random-halves)
                 :generator (cycle [(gen/sleep cycle-seconds)
                                    {:type :info, :f :start}
                                    (gen/sleep cycle-seconds)
                                    {:type :info, :f :stop}])}))
