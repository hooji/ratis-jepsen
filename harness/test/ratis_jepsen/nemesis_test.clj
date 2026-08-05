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

(ns ratis-jepsen.nemesis-test
  "Unit tests for the nemesis pure parts (no cluster): the CLI kinds
  surface, the fault/heal vocabulary the liveness checker gates on,
  minority sizing, leader-biased target selection, segment shapes (the
  Job 04 partition cycle must survive unchanged), and the mixed
  interleave."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [ratis-jepsen.nemesis :as nemesis]))

(deftest cli-kinds-surface
  (is (= #{"none" "partition" "crash" "pause" "mixed"} nemesis/kinds)))

(deftest fault-heal-vocabulary
  (testing "every fault has exactly one heal; no f plays both roles"
    (is (= #{:start :crash :pause} nemesis/fault-fs))
    (is (= #{:stop :restart :resume} nemesis/heal-fs))
    (is (empty? (set/intersection nemesis/fault-fs nemesis/heal-fs)))
    (is (= (count nemesis/fault-fs)
           (count (set (vals nemesis/fault->heal)))))))

(deftest max-minority-sizing
  (testing "survivors always keep a majority"
    (is (= 2 (nemesis/max-minority 5)))   ; the contract topology
    (is (= 1 (nemesis/max-minority 4)))   ; even n: killing 2 of 4 would
                                          ; leave 2 < majority 3
    (is (= 1 (nemesis/max-minority 3)))
    (is (= 3 (nemesis/max-minority 7)))))

(deftest target-count-bounds
  (testing "always 1..max-minority for the 5-voter group"
    (let [counts (set (repeatedly 200 #(nemesis/target-count 5)))]
      (is (= #{1 2} counts)))))

(def voters ["n1" "n2" "n3" "n4" "n5"])

(deftest select-targets-selection
  (testing "size respected, targets distinct and drawn from the nodes"
    (dotimes [_ 50]
      (let [ts (nemesis/select-targets voters nil 2)]
        (is (= 2 (count ts)))
        (is (= 2 (count (set ts))))
        (is (every? (set voters) ts)))))
  (testing "a leader is forced into the set when given"
    (dotimes [_ 50]
      (let [ts (nemesis/select-targets voters "n3" 2)]
        (is (some #{"n3"} ts))
        (is (= 2 (count (set ts))))))
    (is (= ["n5"] (nemesis/select-targets voters "n5" 1))))
  (testing "a leader not in the node list does not bias"
    (dotimes [_ 20]
      (let [ts (nemesis/select-targets voters "n9" 2)]
        (is (= 2 (count ts)))
        (is (every? (set voters) ts))
        (is (not-any? #{"n9"} ts)))))
  (testing "size larger than the node list clamps"
    (is (= 5 (count (nemesis/select-targets voters nil 99))))
    (is (= 5 (count (nemesis/select-targets voters "n1" 99))))))

;; ---------------------------------------------------------------------------
;; Segments and generators
;; ---------------------------------------------------------------------------

(def partition-segment-shape
  "The Job 04 cycle, verbatim — changing this changes existing partition
  runs and requires a brief."
  [{:type :sleep, :value 15}
   {:type :info, :f :start}
   {:type :sleep, :value 15}
   {:type :info, :f :stop}])

(def crash-segment-shape
  [{:type :sleep, :value 20}
   {:type :info, :f :crash}
   {:type :sleep, :value 10}
   {:type :info, :f :restart}])

(def pause-segment-shape
  [{:type :sleep, :value 25}
   {:type :info, :f :pause}
   {:type :sleep, :value 5}
   {:type :info, :f :resume}])

(def default-cycles (nemesis/cycles {}))

(deftest segment-shapes
  (testing "each segment: calm sleep, fault, fault-window sleep, heal"
    (is (= partition-segment-shape (nemesis/partition-segment default-cycles)))
    (is (= crash-segment-shape (nemesis/crash-segment default-cycles)))
    (is (= pause-segment-shape (nemesis/pause-segment default-cycles)))))

(deftest cycles-are-configurable
  (testing "CLI options override crash/pause; partition stays pinned"
    (let [cs (nemesis/cycles {:crash-calm-s 90 :crash-fault-s 10
                              :pause-fault-s 7})]
      (is (= {:calm-s 90 :fault-s 10} (:crash cs)))
      (is (= {:calm-s 25 :fault-s 7} (:pause cs)))
      (is (= {:calm-s 15 :fault-s 15} (:partition cs)))
      (is (= [{:type :sleep, :value 90}
              {:type :info, :f :crash}
              {:type :sleep, :value 10}
              {:type :info, :f :restart}]
             (nemesis/crash-segment cs))))))

(deftest mixed-generator-interleaves-whole-segments
  (let [segments (->> (nemesis/mixed-generator default-cycles)
                      (take 400)
                      (partition 4)
                      (map vec))
        known    #{partition-segment-shape crash-segment-shape
                   pause-segment-shape}]
    (testing "every 4-element group is exactly one of the three segments
              (segments are atomic — no interleaving inside a cycle)"
      (is (every? known segments)))
    (testing "all three kinds appear (100 draws; P[miss] ~ (2/3)^100)"
      (is (= known (set segments))))))

(deftest package-kinds
  (testing "none: noop nemesis, idle generator"
    (let [{:keys [nemesis generator]} (nemesis/package "none")]
      (is (some? nemesis))
      (is (nil? generator))))
  (testing "partition: generator opens with the unchanged Job 04 cycle"
    (let [{:keys [nemesis generator]} (nemesis/package "partition")]
      (is (some? nemesis))
      (is (= partition-segment-shape (take 4 generator)))))
  (testing "crash and pause cycle their briefs' defaults"
    (is (= crash-segment-shape
           (take 4 (:generator (nemesis/package "crash")))))
    (is (= pause-segment-shape
           (take 4 (:generator (nemesis/package "pause"))))))
  (testing "mixed: an infinite segment stream"
    (is (= 40 (count (take 40 (:generator (nemesis/package "mixed"))))))))
