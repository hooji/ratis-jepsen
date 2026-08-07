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

;; ---------------------------------------------------------------------------
;; Install-snapshot evidence (Job 07) — the pure decision parts. The
;; log-line fixtures live in install-snapshot-evidence-counting below,
;; using lines observed verbatim on live snapshot-churn runs.
;; ---------------------------------------------------------------------------

(deftest churn-ops-detection
  (testing "a history with churn nemesis ops owes evidence"
    (is (checker/churn-ops? (history [(nem 10 :churn-kill)
                                      (nem 10.5 :churn-kill)]))))
  (testing "client ops and other nemeses do not"
    (is (not (checker/churn-ops?
               (history (ok-traffic 0 5)
                        [(nem 10 :crash) (nem 10.5 :crash)
                         (nem 20 :restart) (nem 21 :restart)
                         (nem 30 :transfer) (nem 30.5 :transfer)]))))))

(deftest evidence-verdict-decision
  (let [none {:total 0 :counts {"n1" {:send 0 :receive 0}}}
        some {:total 3 :counts {"n1" {:send 2 :receive 0}
                                "n2" {:send 0 :receive 1}}}]
    (testing "churn ran and evidence exists: valid, counts reported"
      (let [v (checker/evidence-verdict true some)]
        (is (true? (:valid? v)))
        (is (= 3 (:total v)))))
    (testing "churn ran and NO install-snapshot evidence: the distinct
              failure — a churn run that tested nothing is broken"
      (let [v (checker/evidence-verdict true none)]
        (is (false? (:valid? v)))
        (is (= :no-install-snapshot-evidence (:error v)))))
    (testing "no churn in the history: no evidence owed (a mixed-all run
              may legitimately draw zero churn segments)"
      (let [v (checker/evidence-verdict false none)]
        (is (true? (:valid? v)))
        (is (some? (:note v)))))))

(def observed-send-line
  "Verbatim from the Job 07 shakedown (store …snapshot-churn/20260805T171416.540Z, n5)."
  (str "2026-08-05 17:15:21.901 [n5@group-ABBC16E54704->n2-GrpcLogAppender-"
       "LogAppenderDaemon] INFO org.apache.ratis.grpc.server.GrpcLogAppender"
       " - n5@group-ABBC16E54704->n2-GrpcLogAppender: followerNextIndex = "
       "1048 but logStartIndex = 1103, send snapshot SingleFileSnapshotInfo"
       "(t:4, i:1239):[/var/lib/ratis-kv/724d1912-848e-4e0f-a7e0-abbc16e547"
       "04/sm/snapshot.4_1239] to follower"))

(def observed-receive-line
  "Verbatim from the same run (n2)."
  (str "2026-08-05 17:15:26.968 [grpc-default-executor-5] INFO "
       "org.apache.ratis.server.impl.SnapshotInstallationHandler - "
       "n2@group-ABBC16E54704: receive installSnapshot: n5->n2#0-t4,"
       "chunk:240a2bc3-5401-4e5f-b1de-0123456789ab,0"))

(deftest install-snapshot-evidence-counting
  (testing "observed real lines match; unrelated lines do not"
    (let [logs {"n5" (str "boot line\n" observed-send-line "\n"
                          observed-send-line "\nother line\n")
                "n2" (str observed-receive-line "\n"
                          "2026-08-05 17:15:22.0 INFO ... - n2: changes "
                          "role from FOLLOWER to CANDIDATE at term 5\n")
                "n1" "nothing relevant\n"
                "n3" nil}   ; a node with no collected log
          {:keys [total counts]}
          (checker/count-install-evidence
            logs checker/install-snapshot-patterns)]
      (is (= 3 total))
      (is (= {:send 2 :receive 0} (get counts "n5")))
      (is (= {:send 0 :receive 1} (get counts "n2")))
      (is (= {:send 0 :receive 0} (get counts "n1")))
      (is (= {:send 0 :receive 0} (get counts "n3")))))
  (testing "the zero-evidence fixture that must convict a churn run"
    (let [logs {"n1" "plain raft chatter\n" "n2" "" "n3" nil}
          ev   (checker/count-install-evidence
                 logs checker/install-snapshot-patterns)]
      (is (zero? (:total ev)))
      (is (= :no-install-snapshot-evidence
             (:error (checker/evidence-verdict true ev)))))))

;; ---------------------------------------------------------------------------
;; Membership evidence (Job 08). Conf-line fixtures are verbatim from the
;; SUT's JoinModeTest run (2026-08-05, in-JVM mini cluster): a join
;; committed as the transitional (old,new) entry at index 3 and its
;; stable follow-up at index 5; index 0 is the elected leader's initial
;; conf. Only transitional lines (old=peers:…) may count — every new
;; leader re-appends the current conf as a STABLE entry at its startup
;; index (LeaderStateImpl.StartupLogEntry at 3.2.2), so elections would
;; otherwise masquerade as conf changes.
;; ---------------------------------------------------------------------------

(def observed-initial-conf-line
  (str "2026-08-05 20:49:18.057 [n1@group-ABBC16E54704-LeaderElection1] "
       "INFO org.apache.ratis.server.RaftServer$Division - "
       "n1@group-ABBC16E54704: set configuration conf: {index: 0, "
       "cur=peers:[n1|127.0.0.1:34283, n2|127.0.0.1:41851, "
       "n3|127.0.0.1:35607]|listeners:[], old=null}"))

(def observed-transitional-conf-line
  (str "2026-08-05 20:49:18.481 [n1@group-ABBC16E54704-LeaderStateImpl] "
       "INFO org.apache.ratis.server.RaftServer$Division - "
       "n1@group-ABBC16E54704: set configuration conf: {index: 3, "
       "cur=peers:[n1|127.0.0.1:34283, n2|127.0.0.1:41851, "
       "n3|127.0.0.1:35607, n4|127.0.0.1:39429]|listeners:[], "
       "old=peers:[n1|127.0.0.1:34283, n2|127.0.0.1:41851, "
       "n3|127.0.0.1:35607]|listeners:[]}"))

(def observed-stable-conf-line
  (str "2026-08-05 20:49:18.495 [n1@group-ABBC16E54704-LeaderStateImpl] "
       "INFO org.apache.ratis.server.RaftServer$Division - "
       "n1@group-ABBC16E54704: set configuration conf: {index: 5, "
       "cur=peers:[n1|127.0.0.1:34283, n2|127.0.0.1:41851, "
       "n3|127.0.0.1:35607, n4|127.0.0.1:39429]|listeners:[], old=null}"))

(deftest conf-transition-index-extraction
  (testing "transitional lines count once per index across all nodes;
            stable lines (initial conf, leader startup re-appends, the
            committed follow-up) never do"
    (is (= [3]
           (checker/conf-transition-indexes
             {"n1" (str observed-initial-conf-line "\n"
                        observed-transitional-conf-line "\n"
                        observed-stable-conf-line "\n")
              ;; the same transitional entry replicated to another node —
              ;; deduplicated by index
              "n2" observed-transitional-conf-line
              "n3" nil}))))
  (testing "distinct transitional indexes accumulate, sorted"
    (is (= [3 9]
           (checker/conf-transition-indexes
             {"n1" observed-transitional-conf-line
              "n2" (clojure.string/replace observed-transitional-conf-line
                                           "{index: 3," "{index: 9,")}))))
  (testing "no logs, no indexes"
    (is (= [] (checker/conf-transition-indexes {"n1" nil "n2" ""})))))

(deftest membership-ops-detection
  (testing "membership moves and the probe owe evidence"
    (is (checker/membership-ops? (history [(nem 10 :member-add)
                                           (nem 10.5 :member-add)])))
    (is (checker/membership-ops? (history [(nem 10 :listener-add)
                                           (nem 10.5 :listener-add)]))))
  (testing "client ops and other nemeses do not"
    (is (not (checker/membership-ops?
               (history (ok-traffic 0 5)
                        [(nem 10 :crash) (nem 10.5 :crash)
                         (nem 30 :transfer) (nem 30.5 :transfer)]))))))

(deftest membership-verdict-decision
  (testing "required and enough transitions: valid, counts reported"
    (let [v (checker/membership-verdict true 2 [3 9 14])]
      (is (true? (:valid? v)))
      (is (= 3 (:transitions v)))))
  (testing "required and too few: the distinct conf-change failure"
    (let [v (checker/membership-verdict true 2 [3])]
      (is (false? (:valid? v)))
      (is (= :no-conf-change-evidence (:error v)))))
  (testing "required and zero: same distinct failure"
    (is (= :no-conf-change-evidence
           (:error (checker/membership-verdict true 2 [])))))
  (testing "not required: valid with a note, counts still reported"
    (let [v (checker/membership-verdict false 2 [])]
      (is (true? (:valid? v)))
      (is (some? (:note v))))))

(deftest joined-nodes-extraction
  (let [h (history
            [(nem 10 :member-add)
             (assoc (nem 12 :member-add)
                    :value {:move :add, :target "n6"
                            :result {:success? true}})
             (nem 20 :member-add)
             (assoc (nem 22 :member-add)
                    :value {:move :add, :target "n7"
                            :result {:error [:x "boom"]}})
             (nem 30 :member-replace-done)
             (assoc (nem 33 :member-replace-done)
                    :value {:move :replace-done, :victim "n2"
                            :add {:target "n7"
                                  :result {:success? true}}})])]
    (testing "successful adds count — from :member-add and from the add
              half of :member-replace-done; failed adds do not"
      (is (= ["n6" "n7"] (checker/joined-nodes h))))))

(deftest joiner-install-verdict-decision
  (testing "not required: valid with a note"
    (is (true? (:valid? (checker/joiner-install-verdict
                          false [] {})))))
  (testing "required but nothing joined: distinct error"
    (let [v (checker/joiner-install-verdict true [] {"n6" 3})]
      (is (false? (:valid? v)))
      (is (= :no-committed-join (:error v)))))
  (testing "joined but no receive evidence on any joiner: distinct error"
    (let [v (checker/joiner-install-verdict
              true ["n6"] {"n6" 0, "n3" 7})]
      (is (false? (:valid? v)))
      (is (= :no-joiner-install-evidence (:error v)))))
  (testing "a joiner with receive evidence: valid, counts quoted"
    (let [v (checker/joiner-install-verdict
              true ["n6" "n7"] {"n6" 2, "n7" 0})]
      (is (true? (:valid? v)))
      (is (= ["n6"] (:joined-with-installs v)))
      (is (= {"n6" 2, "n7" 0} (:receive-counts v))))))

;; ---------------------------------------------------------------------------
;; Counter checking (Job 09). Fabricated per-key histories: ops in the
;; plain per-key shape the independent checker hands over (tuples already
;; unwrapped). Processes invoke serially, like real workers.
;; ---------------------------------------------------------------------------

(defn- cop
  "One client op for counter histories."
  ([process type f value] (cop process type f value nil))
  ([process type f value extra]
   (merge {:process process, :type type, :f f, :value value} extra)))

(deftest counter-exactly-once-clean-history-passes
  ;; Two workers: three :ok adds (5+1+2=8), interleaved reads inside
  ;; bounds, a final read of exactly 8.
  (let [h [(cop 0 :invoke :add 5) (cop 0 :ok :add 5 {:observed 5})
           (cop 1 :invoke :add 1) (cop 1 :ok :add 1 {:observed 6})
           (cop 0 :invoke :read nil) (cop 0 :ok :read 6)
           (cop 1 :invoke :add 2) (cop 1 :ok :add 2 {:observed 8})
           (cop 0 :invoke :read nil {:final? true})
           (cop 0 :ok :read 8)]
        v (checker/check-counter-key h)]
    (is (true? (:valid? v)))
    (is (= {:ok 3 :info 0 :fail 0 :pending 0} (:adds v)))
    (is (= 8 (:ok-sum v)))
    (is (= 8 (get-in v [:final-read :v])))))

(deftest counter-convicts-a-double-count
  ;; The Q14 shape: two :ok adds (3+4=7), no :info slack, and a final
  ;; read of 10 — one add counted twice. upper = 7 < 10 ⇒ conviction.
  (let [h [(cop 0 :invoke :add 3) (cop 0 :ok :add 3)
           (cop 0 :invoke :add 4) (cop 0 :ok :add 4)
           (cop 0 :invoke :read nil {:final? true})
           (cop 0 :ok :read 10)]
        v (checker/check-counter-key h)]
    (is (false? (:valid? v)))
    (is (= :double-count (:kind (first (:violations v)))))
    (is (= 7 (:upper (first (:violations v)))))))

(deftest counter-convicts-a-lost-update
  ;; An :ok add that never made it: final read below the :ok sum.
  (let [h [(cop 0 :invoke :add 3) (cop 0 :ok :add 3)
           (cop 0 :invoke :add 4) (cop 0 :ok :add 4)
           (cop 0 :invoke :read nil {:final? true})
           (cop 0 :ok :read 3)]
        v (checker/check-counter-key h)]
    (is (false? (:valid? v)))
    (is (= :lost-update (:kind (first (:violations v)))))
    (is (= 7 (:lower (first (:violations v)))))))

(deftest counter-info-adds-count-zero-or-once
  ;; One :ok add (5) and one :info add (2): a final read of 5 (info never
  ;; applied) and one of 7 (info applied once) are BOTH legal; 9 (info
  ;; applied twice) is not.
  (let [base [(cop 0 :invoke :add 5) (cop 0 :ok :add 5)
              (cop 1 :invoke :add 2) (cop 1 :info :add 2)]
        with-final (fn [v] (conj (vec base)
                                 (cop 0 :invoke :read nil {:final? true})
                                 (cop 0 :ok :read v)))]
    (is (true? (:valid? (checker/check-counter-key (with-final 5)))))
    (is (true? (:valid? (checker/check-counter-key (with-final 7)))))
    (let [v (checker/check-counter-key (with-final 9))]
      (is (false? (:valid? v)))
      (is (= :double-count (:kind (first (:violations v))))))))

(deftest counter-fail-adds-are-definitely-excluded
  ;; A :fail add tightens the upper bound (outcome-map guarantee:
  ;; definitely not applied): ok 5 + fail 3 ⇒ a read of 8 convicts.
  (let [h [(cop 0 :invoke :add 5) (cop 0 :ok :add 5)
           (cop 1 :invoke :add 3) (cop 1 :fail :add 3)
           (cop 0 :invoke :read nil {:final? true})
           (cop 0 :ok :read 8)]
        v (checker/check-counter-key h)]
    (is (false? (:valid? v)))
    (is (= :double-count (:kind (first (:violations v)))))))

(deftest counter-pending-adds-count-zero-or-once
  ;; A worker crashed mid-add (invocation, no completion): like :info,
  ;; 0-or-1.
  (let [h [(cop 0 :invoke :add 5) (cop 0 :ok :add 5)
           (cop 1 :invoke :add 2)   ; never completes
           (cop 0 :invoke :read nil {:final? true})
           (cop 0 :ok :read 7)]
        v (checker/check-counter-key h)]
    (is (true? (:valid? v)))
    (is (= 1 (get-in v [:adds :pending])))))

(deftest counter-mid-run-read-bounds
  ;; A read invoked BEFORE an add completes may or may not include it
  ;; (upper counts adds invoked before the read completed), but a read
  ;; invoked AFTER an :ok add completes must include it (lower).
  (let [ok  [(cop 0 :invoke :add 4) (cop 0 :ok :add 4)
             (cop 1 :invoke :read nil) (cop 1 :ok :read 4)]
        bad [(cop 0 :invoke :add 4) (cop 0 :ok :add 4)
             (cop 1 :invoke :read nil) (cop 1 :ok :read 0)]]
    (is (true? (:valid? (checker/check-counter-key ok))))
    (let [v (checker/check-counter-key bad)]
      (is (false? (:valid? v)))
      (is (= :lost-update (:kind (first (:violations v))))))))

(deftest counter-duplicate-observed-totals-convict
  ;; Two :ok adds reporting the SAME post-apply total: with adds-only
  ;; keys and positive deltas each apply's total is unique, so a shared
  ;; total means the reply cache handed one op another op's reply — the
  ;; repliedIndex-linearizability signal. (Bounds alone can't see this.)
  (let [h [(cop 0 :invoke :add 1) (cop 0 :ok :add 1 {:observed 3})
           (cop 1 :invoke :add 2) (cop 1 :ok :add 2 {:observed 3})
           (cop 0 :invoke :read nil {:final? true})
           (cop 0 :ok :read 3)]
        v (checker/check-counter-key h)]
    (is (false? (:valid? v)))
    (is (some #(= :duplicate-observed-value (:kind %)) (:violations v)))))

;; ---------------------------------------------------------------------------
;; Retry evidence (Job 09)
;; ---------------------------------------------------------------------------

(deftest retry-totals-counting
  (let [h [{:process 0 :type :invoke :f :add :value 1}
           {:process 0 :type :ok :f :add :value 1 :retries 3}
           {:process 1 :type :invoke :f :read :value nil}
           {:process 1 :type :fail :f :read :value nil :retries 2}
           {:process :nemesis :type :info :f :crash :retries 9} ; not a client op
           {:process 2 :type :invoke :f :add :value 1}
           {:process 2 :type :ok :f :add :value 1}]]
    (is (= {:total 5 :ops 2 :by-f {:add 3 :read 2}}
           (checker/retry-totals h)))))

(deftest retry-verdict-decision
  (testing "required and retries happened: valid, counts reported"
    (let [v (checker/retry-verdict true {:total 41 :ops 12 :by-f {:add 30 :read 11}})]
      (is (true? (:valid? v)))
      (is (= 41 (:total v)))))
  (testing "required and ZERO retries: the distinct dedup-law failure"
    (let [v (checker/retry-verdict true {:total 0 :ops 0 :by-f {}})]
      (is (false? (:valid? v)))
      (is (= :no-retry-evidence (:error v)))))
  (testing "not required (calm run): valid with a note"
    (let [v (checker/retry-verdict false {:total 0 :ops 0 :by-f {}})]
      (is (true? (:valid? v)))
      (is (some? (:note v))))))

(deftest counter-absent-reads-count-as-zero
  ;; GET of an untouched counter replies ABSENT (:value nil): that is a
  ;; legal read of 0 before any add lands (found live — the first reads
  ;; of a key race its first add), and a LYING absent after an :ok add
  ;; is a lost update.
  (testing "an early absent read is a read of 0"
    (let [h [(cop 0 :invoke :read nil) (cop 0 :ok :read nil)
             (cop 1 :invoke :add 5) (cop 1 :ok :add 5)
             (cop 0 :invoke :read nil {:final? true})
             (cop 0 :ok :read 5)]]
      (is (true? (:valid? (checker/check-counter-key h))))))
  (testing "absent AFTER an :ok add convicts as a lost update"
    (let [h [(cop 1 :invoke :add 5) (cop 1 :ok :add 5)
             (cop 0 :invoke :read nil {:final? true})
             (cop 0 :ok :read nil)]
          v (checker/check-counter-key h)]
      (is (false? (:valid? v)))
      (is (= :lost-update (:kind (first (:violations v))))))))

(deftest counter-observed-totals-are-bounds-checked
  ;; Job 09 second pass: an :ok add's reported total pins the state at
  ;; its apply — a double-apply is visible in the TOTALS even when no
  ;; read lands nearby (the Q14 forensic, automated). Here: two
  ;; sequential adds of 3 and 4; the second reports total 10, i.e. a
  ;; pre-state of 6 > the only possible pre-state 3 — the first add
  ;; applied twice.
  (testing "an impossible pre-state convicts without any read"
    (let [h [(cop 0 :invoke :add 3) (cop 0 :ok :add 3 {:observed 3})
             (cop 0 :invoke :add 4) (cop 0 :ok :add 4 {:observed 10})]
          v (checker/check-counter-key h)]
      (is (false? (:valid? v)))
      (is (= :double-count (:kind (first (:violations v)))))))
  (testing "consistent totals pass"
    (let [h [(cop 0 :invoke :add 3) (cop 0 :ok :add 3 {:observed 3})
             (cop 0 :invoke :add 4) (cop 0 :ok :add 4 {:observed 7})]]
      (is (true? (:valid? (checker/check-counter-key h))))))
  (testing "a dedup'd retry's CACHED total stays legal despite the late
            completion (upper widens with completion, lower pins to
            invocation)"
    ;; op A applies first (total 2) but completes LAST (cached reply
    ;; delivered on a retry); op B applies second (total 5).
    (let [h [(cop 0 :invoke :add 2)
             (cop 1 :invoke :add 3) (cop 1 :ok :add 3 {:observed 5})
             (cop 0 :ok :add 2 {:observed 2})]]
      (is (true? (:valid? (checker/check-counter-key h)))))))

;; ---------------------------------------------------------------------------
;; Durability-fault evidence (Job 11, M4)
;; ---------------------------------------------------------------------------

(def lazyfs-drop-log
  "A lazyfs log slice around a clear-cache, phrasing from source at the
  pinned commit (main.cpp spdlog calls)."
  (str "[2026-08-07 10:00:00.000] [info] [lazyfs.faults.worker]: "
       "waiting for fault commands...\n"
       "[2026-08-07 10:01:00.000] [info] [lazyfs.faults.worker]: "
       "received 'lazyfs::clear-cache'\n"))

(def lazyfs-torn-log
  "A lazyfs log slice around a fired torn-op (arming ack, persisted-part
  write, self-kill)."
  (str "[2026-08-07 10:02:00.000] [info] [lazyfs.faults.worker]: "
       "configured successfully 'lazyfs::torn-op::file=/var/lib/"
       "ratis-kv.root/g/current/log_inprogress_0::parts=3::persist=1"
       "::occurrence=3'\n"
       "[2026-08-07 10:02:01.000] [warning] [lazyfs.faults]: Write to "
       "path /var/lib/ratis-kv.root/g/current/log_inprogress_0: will "
       "persist 1365 bytes from offset 4096\n"
       "[2026-08-07 10:02:01.001] [critical] Killing LazyFS pid 137!\n"))

(deftest durability-evidence-counting
  (let [counts (checker/count-durability-evidence
                 {"n1" lazyfs-drop-log
                  "n2" lazyfs-torn-log
                  "n3" nil})]
    (is (= 1 (get-in counts [:counts "n1" :clear-cache-ack])))
    (is (= 0 (get-in counts [:counts "n1" :torn-fired])))
    (is (= 1 (get-in counts [:counts "n2" :torn-armed])))
    (is (= 1 (get-in counts [:counts "n2" :torn-fired])))
    (is (= 1 (get-in counts [:counts "n2" :lazyfs-selfkill])))
    (is (= 0 (get-in counts [:counts "n3" :clear-cache-ack])))))

(deftest durability-ops-detection
  (let [drop-op {:process :nemesis, :type :info, :f :unsync-drop}
        all-op  {:process :nemesis, :type :info, :f :unsync-drop-all}
        torn-op {:process :nemesis, :type :info, :f :torn-write}
        client  {:process 0, :type :ok, :f :read, :value 1}]
    (is (checker/unsync-ops? [client drop-op]))
    (is (checker/unsync-ops? [client all-op]))
    (is (not (checker/unsync-ops? [client torn-op])))
    (is (checker/torn-ops? [client torn-op]))
    (is (not (checker/torn-ops? [client drop-op])))
    (testing "a client op with the same :f shape never counts"
      (is (not (checker/unsync-ops? [{:process 3, :type :invoke,
                                         :f :unsync-drop}]))))))

(deftest torn-restart-result-extraction
  (let [history [{:process :nemesis, :type :info, :f :torn-restart}
                 {:process :nemesis, :type :info, :f :torn-restart,
                  :value {:outcome :refused-start, :victim "n2",
                          :fired true}}
                 {:process 0, :type :ok, :f :read, :value 3}]]
    (is (= [{:outcome :refused-start, :victim "n2", :fired true}]
           (checker/torn-restart-results history)))))

(deftest durability-verdict-decision
  (let [drop-ev (checker/count-durability-evidence
                  {"n1" lazyfs-drop-log, "n2" nil})
        torn-ev (checker/count-durability-evidence
                  {"n1" nil, "n2" lazyfs-torn-log})
        no-ev   (checker/count-durability-evidence
                  {"n1" nil, "n2" nil})]
    (testing "not required: valid, counts still reported"
      (let [v (checker/durability-verdict false false no-ev [])]
        (is (:valid? v))
        (is (= 0 (:clear-cache-acks v)))))
    (testing "unsync required + at least one ack: valid"
      (is (:valid? (checker/durability-verdict true false drop-ev []))))
    (testing "unsync required + zero acks: the broken-test conviction"
      (let [v (checker/durability-verdict true false no-ev [])]
        (is (not (:valid? v)))
        (is (= :no-durability-fault-evidence (:error v)))))
    (testing "torn required + a fired tear + a recorded recovery: valid"
      (is (:valid? (checker/durability-verdict
                     false true torn-ev
                     [{:outcome :refused-start, :fired true}]))))
    (testing "torn required + armed but never fired: conviction"
      (let [armed-only (checker/count-durability-evidence
                         {"n1" (str "[info] [lazyfs.faults.worker]: "
                                    "configured successfully "
                                    "'lazyfs::torn-op::file=/x'\n")})
            v (checker/durability-verdict false true armed-only [])]
        (is (not (:valid? v)))
        (is (= :no-durability-fault-evidence (:error v)))))
    (testing "torn required + fired but the remount unproven: the
              recovery half never ran on lazyfs — conviction"
      (let [v (checker/durability-verdict
                false true torn-ev
                [{:outcome :remount-unproven, :error "boom"}])]
        (is (not (:valid? v)))
        (is (= :torn-recovery-unproven (:error v)))))
    (testing ":started and :wedged are recorded outcomes, not failures"
      (is (:valid? (checker/durability-verdict
                     false true torn-ev [{:outcome :started}])))
      (is (:valid? (checker/durability-verdict
                     false true torn-ev [{:outcome :wedged}]))))))
