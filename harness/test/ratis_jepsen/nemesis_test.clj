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
  (is (= #{"none" "partition" "crash" "pause" "mixed"
           "snapshot-churn" "transfer" "membership"
           "membership-snapshot-churn" "listener-probe" "mixed-all"}
         nemesis/kinds)))

(deftest fault-heal-vocabulary
  (testing "every fault has exactly one heal; no f plays both roles"
    (is (= #{:start :crash :pause :churn-kill :member-replace-dead}
           nemesis/fault-fs))
    (is (= #{:stop :restart :resume :churn-restart :member-replace-done}
           nemesis/heal-fs))
    (is (empty? (set/intersection nemesis/fault-fs nemesis/heal-fs)))
    (is (= (count nemesis/fault-fs)
           (count (set (vals nemesis/fault->heal))))))
  (testing "action fs act without gating: disjoint from faults and heals.
            Membership adds/removes and the whole listener probe are
            actions — a healthy majority persists through them, so they
            must not excuse a stall; only replace-dead (a genuinely dead
            voter) gates"
    (is (= #{:churn-transfer :churn-snapshot :transfer
             :member-add :member-remove
             :listener-add :listener-census :listener-promote
             :listener-demote :listener-remove}
           nemesis/action-fs))
    (is (empty? (set/intersection nemesis/action-fs
                                  (set/union nemesis/fault-fs
                                             nemesis/heal-fs))))))

(deftest membership-band
  (testing "the 5±2 band the brief pins"
    (is (= 3 nemesis/min-voters))
    (is (= 7 nemesis/max-voters))))

(deftest initial-membership-state-shape
  (let [s (nemesis/initial-membership-state
            ["n1" "n2" "n3" "n4" "n5" "n6" "n7"]
            ["n1" "n2" "n3" "n4" "n5"])]
    (is (= #{"n1" "n2" "n3" "n4" "n5"} (:voters s)))
    (is (= #{"n6" "n7"} (:pool s)))
    (is (= #{"n6" "n7"} (:dynamic s))))
  (testing "voters not present in the node list are dropped"
    (let [s (nemesis/initial-membership-state
              ["n1" "n2" "n3"] ["n1" "n2" "n3" "n4" "n5"])]
      (is (= #{"n1" "n2" "n3"} (:voters s)))
      (is (= #{} (:pool s))))))

(deftest conf-nodes-selection
  (testing "without membership state: the test's nodes (M0/M1 behavior)"
    (is (= ["n1" "n2"] (nemesis/conf-nodes {:nodes ["n1" "n2"]}))))
  (testing "with membership state: the intended voters, sorted"
    (is (= ["n1" "n3"]
           (nemesis/conf-nodes
             {:nodes ["n1" "n2" "n3"]
              :membership-state (atom {:voters #{"n3" "n1"}
                                       :pool #{"n2"}})})))))

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

(def churn-segment-shape
  [{:type :sleep, :value 15}
   {:type :info, :f :churn-kill}
   {:type :sleep, :value 2}
   {:type :info, :f :churn-transfer}
   {:type :sleep, :value 5}
   {:type :info, :f :churn-snapshot}
   {:type :sleep, :value 3}
   {:type :info, :f :churn-restart}])

(def transfer-segment-shape
  [{:type :sleep, :value 20}
   {:type :info, :f :transfer}])

(deftest m2-segment-shapes
  (testing "churn: calm, kill, transfer (term bump), writes window,
            snapshot, gap, restart"
    (is (= churn-segment-shape (nemesis/churn-segment default-cycles))))
  (testing "transfer: calm, one transfer attempt"
    (is (= transfer-segment-shape (nemesis/transfer-segment default-cycles))))
  (testing "churn cadence is configurable"
    (is (= [{:type :sleep, :value 30}
            {:type :info, :f :churn-kill}
            {:type :sleep, :value 4}
            {:type :info, :f :churn-transfer}
            {:type :sleep, :value 8}
            {:type :info, :f :churn-snapshot}
            {:type :sleep, :value 2}
            {:type :info, :f :churn-restart}]
           (nemesis/churn-segment
             (nemesis/cycles {:churn-calm-s 30
                              :churn-kill-to-transfer-s 4
                              :churn-transfer-to-snapshot-s 8
                              :churn-snapshot-to-restart-s 2}))))
    (is (= {:type :sleep, :value 45}
           (first (nemesis/transfer-segment
                    (nemesis/cycles {:transfer-calm-s 45})))))))

(deftest mixed-generator-interleaves-whole-segments
  (let [segments (->> (nemesis/mixed-generator default-cycles)
                      (take 400)
                      (partition 4)
                      (map vec))
        known    #{partition-segment-shape crash-segment-shape
                   pause-segment-shape}]
    (testing "every 4-element group is exactly one of the three M1
              segments — mixed is unchanged by M2 (segments are atomic;
              no interleaving inside a cycle)"
      (is (every? known segments)))
    (testing "all three kinds appear (100 draws; P[miss] ~ (2/3)^100)"
      (is (= known (set segments))))))

(def member-add-segment-shape
  [{:type :sleep, :value 15}
   {:type :info, :f :member-add}])

(def member-remove-segment-shape
  [{:type :sleep, :value 15}
   {:type :info, :f :member-remove}])

(def member-replace-segment-shape
  [{:type :sleep, :value 15}
   {:type :info, :f :member-replace-dead}
   {:type :sleep, :value 8}
   {:type :info, :f :member-replace-done}])

(def membership-segment-shapes
  #{member-add-segment-shape member-remove-segment-shape
    member-replace-segment-shape})

(deftest membership-segment-shapes-test
  (testing "each move: calm, then the move; replace is the fault pair
            around the dead-and-removed window"
    (is (= member-add-segment-shape
           (nemesis/member-add-segment default-cycles)))
    (is (= member-remove-segment-shape
           (nemesis/member-remove-segment default-cycles)))
    (is (= member-replace-segment-shape
           (nemesis/member-replace-segment default-cycles))))
  (testing "a membership segment is always one of the three moves"
    (dotimes [_ 30]
      (is (contains? membership-segment-shapes
                     (nemesis/membership-segment default-cycles)))))
  (testing "cadence is configurable"
    (is (= [{:type :sleep, :value 40}
            {:type :info, :f :member-replace-dead}
            {:type :sleep, :value 3}
            {:type :info, :f :member-replace-done}]
           (nemesis/member-replace-segment
             (nemesis/cycles {:membership-calm-s 40
                              :membership-replace-dead-s 3}))))))

(defn- split-segments
  "Greedily splits a generator element stream into whole known segments;
  stops at the first unmatched or incomplete tail."
  [elements known]
  (loop [es elements, out []]
    (let [m (some (fn [s] (when (= s (take (count s) es)) s)) known)]
      (if (and m (>= (count es) (count m)))
        (recur (drop (count m) es) (conj out m))
        out))))

(deftest membership-generator-draws-every-move
  (let [segments (split-segments
                   (take 200 (nemesis/membership-generator default-cycles))
                   membership-segment-shapes)]
    (testing "an endless stream of membership segments"
      (is (<= 60 (count segments)))
      (is (= membership-segment-shapes (set segments))))
    (testing "guaranteed coverage: every consecutive block of three
              segments contains each move exactly once (a uniform draw
              starved adds for a whole live run — never again by
              construction)"
      (doseq [block (partition 3 segments)]
        (is (= membership-segment-shapes (set block)))))))

(deftest membership-churn-generator-alternates-churn-first
  (let [known    (conj membership-segment-shapes churn-segment-shape)
        segments (split-segments
                   (take 300 (nemesis/membership-churn-generator
                               default-cycles))
                   known)]
    (testing "strict alternation, churn first: the opening churn cycle
              forces a snapshot before the first join can commit, which
              puts every join on the install-snapshot bootstrap path"
      (is (<= 40 (count segments)))
      (is (= churn-segment-shape (first segments)))
      (doseq [[c m] (partition 2 segments)]
        (is (= churn-segment-shape c))
        (is (contains? membership-segment-shapes m))))
    (testing "the membership half still covers all three moves"
      (is (= membership-segment-shapes
             (set (remove #(= churn-segment-shape %) segments)))))))

(deftest listener-probe-script-shape
  (testing "the probe is finite and scripted: add, census, promote,
            census, demote, remove — all actions, no fault windows"
    (is (= [:listener-add :listener-census :listener-promote
            :listener-census :listener-demote :listener-remove]
           (->> nemesis/listener-probe-script
                (keep :f)
                vec)))
    (is (every? nemesis/action-fs
                (keep :f nemesis/listener-probe-script)))))

(deftest mixed-all-interleaves-all-six-kinds
  (let [known    (into [churn-segment-shape   ; matching full shapes is
                        partition-segment-shape ; unambiguous: every pair
                        crash-segment-shape   ; differs at its fault op
                        pause-segment-shape   ; (element 2) at the latest
                        transfer-segment-shape]
                       membership-segment-shapes)
        ;; 300 elements always ends mid-segment; trim to whole segments
        ;; by consuming until the tail is shorter than the longest shape.
        elements (take 300 (nemesis/mixed-all-generator default-cycles))
        segments (split-segments elements known)
        kind-of  (fn [segment]
                   (if (contains? membership-segment-shapes segment)
                     :membership
                     segment))]
    (testing "the stream is whole segments drawn from all six kinds
              (~70+ draws; P[missing a kind] < 1e-5 — membership counts
              as one kind, its three move shapes pooled)"
      (is (<= 40 (count segments)))
      (is (= (set (map kind-of known)) (set (map kind-of segments)))))))

(deftest package-m2-kinds
  (testing "snapshot-churn and transfer cycle their segments"
    (is (= churn-segment-shape
           (take 8 (:generator (nemesis/package "snapshot-churn")))))
    (is (= transfer-segment-shape
           (take 2 (:generator (nemesis/package "transfer"))))))
  (testing "membership: an endless stream of membership segments"
    (let [segments (split-segments
                     (take 100 (:generator (nemesis/package "membership")))
                     membership-segment-shapes)]
      (is (<= 30 (count segments)))))
  (testing "membership-snapshot-churn: membership + churn segments"
    (is (= 80 (count (take 80 (:generator
                                (nemesis/package
                                  "membership-snapshot-churn")))))))
  (testing "listener-probe: the finite script, verbatim"
    (is (= nemesis/listener-probe-script
           (:generator (nemesis/package "listener-probe")))))
  (testing "mixed-all: an infinite stream"
    (is (= 60 (count (take 60 (:generator (nemesis/package "mixed-all"))))))))

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
