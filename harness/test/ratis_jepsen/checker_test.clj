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

(ns ratis-jepsen.checker-test
  "Unit tests for the liveness checker against fabricated histories
  (the brief's mandated scenarios and the edges around them). All times
  below are seconds, converted to the history's nanosecond :time; checker
  parameters are the defaults: T=60 s window, G=15 s grace,
  10 s max attempt gap."
  (:require [clojure.test :refer [deftest is testing]]
            [ratis-jepsen.checker :as checker]))

(defn- s->ns [s] (long (* s 1e9)))

(defn- invoke [t f] {:process 0, :type :invoke, :f f, :value nil,
                     :time (s->ns t)})
(defn- ok     [t f] {:process 0, :type :ok,     :f f, :value 1,
                     :time (s->ns t)})
(defn- fail   [t f] {:process 0, :type :fail,   :f f, :value nil,
                     :time (s->ns t)})
(defn- info   [t f] {:process 0, :type :info,   :f f, :value nil,
                     :time (s->ns t)})
(defn- nem    [t f] {:process :nemesis, :type :info, :f f, :time (s->ns t)})

(defn- ok-traffic
  "Invoke+:ok pairs every second in [from, to)."
  [from to]
  (mapcat (fn [t] [(invoke t :read) (ok (+ t 0.1) :read)])
          (range from to)))

(defn- stalled-traffic
  "Invoke+:fail pairs every second in [from, to) — clients continuously
  attempting, the cluster acking nothing."
  [from to]
  (mapcat (fn [t] [(invoke t :read) (fail (+ t 0.1) :read)])
          (range from to)))

(defn- history
  "Sorts fabricated ops into history (time) order."
  [& op-seqs]
  (vec (sort-by :time (apply concat op-seqs))))

(defn- check
  ([h] (check h {}))
  ([h opts] (checker/check-liveness h (merge checker/default-opts opts))))

;; ---------------------------------------------------------------------------
;; The brief's three mandated scenarios
;; ---------------------------------------------------------------------------

(deftest calm-window-stall-is-flagged
  ;; Progress for 10 s, then 70 s of continuous attempts with zero acks —
  ;; no nemesis anywhere. The defining liveness violation.
  (let [h (history (ok-traffic 0 10)
                   (stalled-traffic 10 80)
                   [(invoke 80 :read) (ok 80.5 :read)])
        r (check h)]
    (is (false? (:valid? r)))
    (is (= 1 (count (:violations r))))
    (let [v (first (:violations r))]
      (is (<= 60 (:duration-s v)))
      (is (< 0 (:attempts v)))
      ;; The stall the evidence names is the fabricated one.
      (is (<= 10 (:stall-start-s v) 12))
      (is (<= 79 (:stall-end-s v) 81)))))

(deftest stall-during-or-just-after-fault-is-not-flagged
  ;; A crash window [5, 16] (heal completes at 16; grace gates until 31),
  ;; and the cluster stays stalled until 89.5 — a 58.5 s calm-region
  ;; stall, under the 60 s window. Recovery noise after a fault must not
  ;; convict.
  (let [h (history (ok-traffic 0 5)
                   [(nem 5    :crash)
                    (nem 5.5  :crash)     ; targets resolved (completion)
                    (nem 15   :restart)
                    (nem 16   :restart)]  ; heal completion: +15 s grace
                   (stalled-traffic 5 89)
                   [(invoke 89 :read) (ok 89.5 :read)]
                   (ok-traffic 90 95))
        r (check h)]
    (is (true? (:valid? r)))
    (is (= [] (:violations r)))))

(deftest idle-generator-window-is-not-flagged
  ;; The op budget exhausts at 30 s; the nemesis keeps cycling to the
  ;; time limit with no client invocations at all (every register run
  ;; looks like this past its budget). Calm gaps of 100+ s with zero
  ;; invocations must not convict.
  (let [h (history (ok-traffic 0 30)
                   [(nem 40    :crash)
                    (nem 40.5  :crash)
                    (nem 50    :restart)
                    (nem 51    :restart)   ; calm [66, 200]: no invokes
                    (nem 200   :crash)
                    (nem 200.5 :crash)
                    (nem 210   :restart)
                    (nem 211   :restart)])
        r (check h)]
    (is (true? (:valid? r)))
    (is (= [] (:violations r)))))

;; ---------------------------------------------------------------------------
;; Edges around the mandated three
;; ---------------------------------------------------------------------------

(deftest stall-persisting-past-grace-is-flagged
  ;; The RATIS-2523 shape this checker exists for: a crash-restart cycle
  ;; completes (heal at 9, grace to 24), the cluster is majority-healthy —
  ;; and clients get nothing for the next 96 s.
  (let [h (history (ok-traffic 0 5)
                   [(nem 5   :crash)
                    (nem 5.5 :crash)
                    (nem 8   :restart)
                    (nem 9   :restart)]
                   (stalled-traffic 5 120))
        r (check h)]
    (is (false? (:valid? r)))
    (let [v (first (:violations r))]
      (is (<= 60 (:duration-s v)))
      ;; The evidence window sits inside the calm region, not the fault.
      (is (<= 24 (:stall-start-s v))))))

(deftest sparse-attempts-do-not-count-as-continuous
  ;; Two lone attempts 30 s apart inside an otherwise idle 134 s calm gap:
  ;; not "invocations attempted throughout" — each chain covers ~10 s.
  (let [h (history (ok-traffic 0 30)
                   [(nem 40   :crash)
                    (nem 40.5 :crash)
                    (nem 50   :restart)
                    (nem 51   :restart)
                    (nem 200  :crash)
                    (nem 200.5 :crash)
                    (nem 210  :restart)
                    (nem 211  :restart)]
                   [(invoke 100 :read) (fail 100.2 :read)
                    (invoke 130 :read) (fail 130.2 :read)])
        r (check h)]
    (is (true? (:valid? r)))))

(deftest steady-progress-is-valid
  ;; :ok completions every 30 s reset the window; fails in between are
  ;; fine (CAS precondition misses are business as usual).
  (let [h (history (mapcat (fn [t] [(invoke t :cas) (ok (+ t 0.1) :cas)])
                           (range 0 300 30))
                   (mapcat (fn [t] [(invoke t :cas) (fail (+ t 0.1) :cas)])
                           (range 7 300 15)))
        r (check h)]
    (is (true? (:valid? r)))))

(deftest unhealed-fault-gates-through-history-end
  ;; A fault opens at 10 and never heals: everything after it is gated,
  ;; however long the stall. (Whether that run passes is the
  ;; linearizable/stats checkers' business; liveness stays quiet.)
  (let [h (history (ok-traffic 0 10)
                   [(nem 10   :crash)
                    (nem 10.5 :crash)]
                   (stalled-traffic 10 200))
        r (check h)]
    (is (true? (:valid? r)))
    (is (= [] (:violations r)))))

(deftest heal-invocation-without-completion-keeps-the-gate-closed
  ;; The heal was *invoked* at 20 but the history ends before its
  ;; completion (a run cut mid-heal): the heal is only trusted once done,
  ;; so the whole tail stays gated.
  (let [h (history (ok-traffic 0 10)
                   [(nem 10   :crash)
                    (nem 10.5 :crash)
                    (nem 20   :restart)]  ; invocation only — never completes
                   (stalled-traffic 10 200))
        r (check h)]
    (is (true? (:valid? r)))))

(deftest info-completions-are-not-progress
  ;; Writes hanging into ambiguity (:info) are exactly what a stuck
  ;; cluster produces; they must not reset the window.
  (let [h (history (ok-traffic 0 10)
                   (mapcat (fn [t] [(invoke t :write) (info (+ t 0.1) :write)])
                           (range 10 80))
                   [(invoke 80 :write) (ok 80.5 :write)])
        r (check h)]
    (is (false? (:valid? r)))))

(deftest empty-and-client-free-histories-are-valid
  (is (true? (:valid? (check []))))
  (is (true? (:valid? (check (history [(nem 10 :crash) (nem 10.5 :crash)
                                       (nem 20 :restart) (nem 21 :restart)]))))))

;; ---------------------------------------------------------------------------
;; The pure helpers' arithmetic
;; ---------------------------------------------------------------------------

(deftest unhealthy-intervals-pair-by-parity
  (testing "fault invocation opens; heal completion (+grace) closes"
    (is (= [[(s->ns 10) (s->ns 36)]]
           (checker/unhealthy-intervals
             [(nem 10 :crash) (nem 11 :crash)
              (nem 20 :restart) (nem 21 :restart)]
             {:crash :restart}
             (s->ns 15)))))
  (testing "a heal with nothing open closes nothing"
    (is (= [] (checker/unhealthy-intervals
                [(nem 5 :resume) (nem 6 :resume)]
                {:pause :resume}
                (s->ns 15)))))
  (testing "an unhealed fault stays open (nil end)"
    (is (= [[(s->ns 10) nil]]
           (checker/unhealthy-intervals
             [(nem 10 :crash) (nem 11 :crash)]
             {:crash :restart}
             (s->ns 15))))))

(deftest interval-merging-and-complement
  (is (= [[10 40] [100 200]]
         (checker/merge-intervals [[10 30] [25 40] [100 nil]] 200)))
  (is (= [[0 10] [40 100]]
         (checker/calm-regions [[10 40] [100 200]] 0 200)))
  (testing "no faults: one calm region spanning the run"
    (is (= [[0 200]] (checker/calm-regions [] 0 200)))))
