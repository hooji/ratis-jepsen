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

(ns ratis-jepsen.outcome-test
  "Unit tests for THE OUTCOME MAP: one deftest per row of the DESIGN 2.4
  table, exercised with real Ratis 3.2.2 exception instances (every type
  in the table has a public constructor — no stand-ins were needed),
  plus the mechanically-required RaftRetryFailureException rows and the
  reads-are-never-:info sweep."
  (:require [clojure.test :refer [deftest is testing]]
            [ratis-jepsen.env-contract :as env]
            [ratis-jepsen.outcome :as outcome])
  (:import (java.io InterruptedIOException IOException)
           (java.util.concurrent CompletionException ExecutionException
                                 TimeoutException)
           (org.apache.ratis.protocol ClientId RaftClientRequest RaftGroupId
                                      RaftGroupMemberId RaftPeer RaftPeerId)
           (org.apache.ratis.protocol.exceptions
             AlreadyClosedException
             GroupMismatchException
             LeaderNotReadyException
             NotLeaderException
             RaftRetryFailureException
             ReadException
             ReadIndexException
             ResourceUnavailableException
             ServerNotReadyException
             StateMachineException
             TimeoutIOException)
           (org.apache.ratis.retry RetryPolicies)))

;; Real Ratis protocol objects for exception construction.
(def group-id (RaftGroupId/valueOf env/group-uuid))
(def member-id (RaftGroupMemberId/valueOf (RaftPeerId/valueOf "n1") group-id))
(def a-peer (-> (RaftPeer/newBuilder) (.setId "n2") (.setAddress "n2:6000") (.build)))
(def a-request
  (-> (RaftClientRequest/newBuilder)
      (.setClientId (ClientId/randomId))
      (.setServerId (RaftPeerId/valueOf "n1"))
      (.setGroupId group-id)
      (.setCallId 1)
      (.setType (RaftClientRequest/writeRequestType))
      (.build)))

(def not-leader (NotLeaderException. member-id a-peer [a-peer]))
(def leader-not-ready (LeaderNotReadyException. member-id))

(defn- loud? [verdict] (contains? verdict ::outcome/loud))

(defn- classify-all
  "Classifies one outcome for all three op kinds; returns {:write v :cas v :read v}."
  [outcome]
  (into {} (map (fn [k] [k (outcome/classify k outcome)])) outcome/op-kinds))

;; ---------------------------------------------------------------------------
;; Row: reply isSuccess ⇒ :ok — with the CAS precondition exception
;; ---------------------------------------------------------------------------

(deftest row-reply-success
  (testing "write OK ⇒ :ok"
    (is (= {:type :ok} (outcome/classify :write "OK"))))
  (testing "cas OK ⇒ :ok"
    (is (= {:type :ok} (outcome/classify :cas "OK"))))
  (testing "read VAL v ⇒ :ok with the value"
    (is (= {:type :ok :value 42} (outcome/classify :read "VAL 42")))
    (is (= {:type :ok :value -7} (outcome/classify :read "VAL -7"))))
  (testing "read ABSENT ⇒ :ok with nil value"
    (is (= {:type :ok :value nil} (outcome/classify :read "ABSENT")))))

(deftest row-cas-precondition
  (testing "cas MISMATCH ⇒ :fail :precondition (definite, by design)"
    (is (= {:type :fail :error :precondition :current 3}
           (outcome/classify :cas "MISMATCH 3"))))
  (testing "cas ABSENT ⇒ :fail :precondition"
    (is (= {:type :fail :error :precondition}
           (outcome/classify :cas "ABSENT")))))

(deftest row-err-and-malformed-replies
  (testing "ERR reply is a harness/SUT bug: definite :fail, loud, never :info"
    (doseq [kind outcome/op-kinds]
      (let [v (outcome/classify kind "ERR unknown command: XYZ")]
        (is (= :fail (:type v)) (str kind))
        (is (loud? v) (str kind)))))
  (testing "replies impossible for the op kind ⇒ :fail, loud"
    (doseq [[kind reply] [[:write "VAL 5"]
                          [:write "MISMATCH 5"]
                          [:write "ABSENT"]
                          [:cas "VAL 5"]
                          [:read "OK"]
                          [:read "MISMATCH 5"]
                          [:read "gibberish"]
                          [:write "ok"]]]        ; case-sensitive protocol
      (let [v (outcome/classify kind reply)]
        (is (= :fail (:type v)) (str kind " " reply))
        (is (loud? v) (str kind " " reply))))))

;; ---------------------------------------------------------------------------
;; Rows: leadership-shaped rejections ⇒ :info for writes, :fail for reads
;; (DESIGN 2.4 as amended 2026-08-05 after Review 05's false-red: a deposed
;; leader completes appended writes with NotLeaderException and the entries
;; can commit under its successor — never a definite write :fail)
;; ---------------------------------------------------------------------------

(deftest row-not-leader
  (testing "write path: ambiguous — the entry may commit under the successor"
    (is (= {:type :info :error :not-leader} (outcome/classify :write not-leader)))
    (is (= {:type :info :error :not-leader} (outcome/classify :cas not-leader))))
  (testing "read path: no side effect, the read simply did not happen"
    (is (= {:type :fail :error :not-leader} (outcome/classify :read not-leader)))))

(deftest row-leader-not-ready
  (testing "write path: ambiguous"
    (is (= {:type :info :error :leader-not-ready}
           (outcome/classify :write leader-not-ready)))
    (is (= {:type :info :error :leader-not-ready}
           (outcome/classify :cas leader-not-ready))))
  (testing "read path: :fail"
    (is (= {:type :fail :error :leader-not-ready}
           (outcome/classify :read leader-not-ready)))))

(deftest row-server-not-ready
  ;; Job 08: a division that is STARTING (mid-boot) or CLOSED (a removed
  ;; peer's self-shutdown) rejects with ServerNotReadyException — routine
  ;; during crash restarts and membership churn, and quiet: this request
  ;; was rejected, but the invocation may be a retry whose earlier
  ;; attempt applied.
  (let [snr (ServerNotReadyException. "n3 is not in [RUNNING]: current state is CLOSED")]
    (testing "write path: ambiguous, quietly"
      (is (= {:type :info :error :server-not-ready}
             (outcome/classify :write snr)))
      (is (= {:type :info :error :server-not-ready}
             (outcome/classify :cas snr))))
    (testing "read path: :fail"
      (is (= {:type :fail :error :server-not-ready}
             (outcome/classify :read snr))))))

;; ---------------------------------------------------------------------------
;; Rows: definite-failure exceptions ⇒ :fail for writes and reads
;; ---------------------------------------------------------------------------

(deftest row-resource-unavailable
  (let [{:keys [write cas read]} (classify-all (ResourceUnavailableException. "admission control"))]
    (is (= {:type :fail :error :resource-unavailable} write))
    (is (= {:type :fail :error :resource-unavailable} cas))
    (is (= {:type :fail :error :resource-unavailable} read))))

(deftest row-group-mismatch
  (testing ":fail and loud (test-setup bug, flag the run)"
    (doseq [kind outcome/op-kinds]
      (let [v (outcome/classify kind (GroupMismatchException. "wrong group"))]
        (is (= :fail (:type v)) (str kind))
        (is (= :group-mismatch (:error v)) (str kind))
        (is (loud? v) (str kind))))))

(deftest row-state-machine
  (testing ":fail and loud (our SM never throws from apply — SUT bug)"
    (doseq [kind outcome/op-kinds]
      (let [v (outcome/classify kind (StateMachineException. "boom"))]
        (is (= :fail (:type v)) (str kind))
        (is (= :state-machine (:error v)) (str kind))
        (is (loud? v) (str kind))))))

;; ---------------------------------------------------------------------------
;; Row: ReadException / ReadIndexException ⇒ :fail for reads ("—" for writes:
;; cannot legally happen there, so pessimism + loud if it ever does)
;; ---------------------------------------------------------------------------

(deftest row-read-exceptions
  (testing "reads ⇒ :fail (the read never happened)"
    (is (= {:type :fail :error :read}
           (outcome/classify :read (ReadException. "read failed"))))
    (is (= {:type :fail :error :read-index}
           (outcome/classify :read (ReadIndexException. "no read index")))))
  (testing "on the write path these are impossible ⇒ pessimism, loud"
    (doseq [t [(ReadException. "read failed")
               (ReadIndexException. "no read index")]
            kind [:write :cas]]
      (let [v (outcome/classify kind t)]
        (is (= :info (:type v)) (str kind " " (class t)))
        (is (loud? v) (str kind " " (class t)))))))

;; ---------------------------------------------------------------------------
;; Row: Timeout/IOException/AlreadyClosed/interrupt ⇒ :info writes, :fail reads
;; ---------------------------------------------------------------------------

(deftest row-ambiguous-exceptions
  (doseq [[t expected-error] [[(TimeoutIOException. "rpc timeout") :timeout]
                              [(IOException. "connection refused") :io]
                              [(AlreadyClosedException. "client closed") :already-closed]
                              [(InterruptedIOException. "interrupted io") :interrupted]
                              [(InterruptedException. "interrupted") :interrupted]
                              [(TimeoutException. "harness deadline") :harness-timeout]]]
    (testing (str (.getName (class t)) " ⇒ :info for writes")
      (is (= {:type :info :error expected-error} (outcome/classify :write t)))
      (is (= {:type :info :error expected-error} (outcome/classify :cas t))))
    (testing (str (.getName (class t)) " ⇒ :fail for reads")
      (is (= {:type :fail :error expected-error} (outcome/classify :read t))))))

;; ---------------------------------------------------------------------------
;; Row: unknown Throwable ⇒ :info for writes (pessimism) + loud; reads :fail
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Row: LeaderSteppingDownException (M2, reached by leadership transfers)
;; ---------------------------------------------------------------------------

(deftest row-leader-stepping-down
  (let [t (org.apache.ratis.protocol.exceptions.LeaderSteppingDownException.
            "n2 is stepping down")]
    (testing "write path: definite :fail — thrown only by the pre-append
              admission check (RaftServerImpl.checkLeaderState); the
              request was never appended"
      (is (= {:type :fail :error :leader-stepping-down}
             (outcome/classify :write t)))
      (is (= {:type :fail :error :leader-stepping-down}
             (outcome/classify :cas t))))
    (testing "read path: guarded impossible (isReadOnly skips the check)
              ⇒ pessimism + loud"
      (let [v (outcome/classify :read t)]
        (is (= :fail (:type v)))
        (is (loud? v))))))

(deftest row-unknown-throwable
  (doseq [t [(RuntimeException. "surprise")
             (IllegalStateException. "unexpected state")
             (AssertionError. "assert")
             ;; An unrecognized RaftException subtype must land here too,
             ;; not in the quiet generic-IO row.
             (org.apache.ratis.protocol.exceptions.NotReplicatedException.
               1 org.apache.ratis.proto.RaftProtos$ReplicationLevel/MAJORITY 2)]]
    (testing (str (.getName (class t)) " ⇒ :info + loud for writes")
      (doseq [kind [:write :cas]]
        (let [v (outcome/classify kind t)]
          (is (= :info (:type v)) (str kind " " (class t)))
          (is (loud? v) (str kind " " (class t))))))
    (testing (str (.getName (class t)) " ⇒ :fail + loud for reads")
      (let [v (outcome/classify :read t)]
        (is (= :fail (:type v)) (str (class t)))
        (is (loud? v) (str (class t)))))))

;; ---------------------------------------------------------------------------
;; Rows: RaftRetryFailureException — retry exhaustion (see outcome ns
;; docstring; the null-cause form is how NotLeader/LeaderNotReady surface
;; through the client's bounded retry funnel)
;; ---------------------------------------------------------------------------

(deftest row-retry-failure-null-cause
  (let [t (RaftRetryFailureException. a-request 4 (RetryPolicies/noRetry) nil)]
    (testing "write path: every attempt was leadership-rejected, but any of
              them may have been appended by a leader deposed before
              acking ⇒ :info"
      (is (= {:type :info :error :not-leader-or-not-ready}
             (outcome/classify :write t)))
      (is (= {:type :info :error :not-leader-or-not-ready}
             (outcome/classify :cas t))))
    (testing "read path: :fail"
      (is (= {:type :fail :error :not-leader-or-not-ready}
             (outcome/classify :read t))))))

(deftest row-retry-failure-with-cause
  (testing "non-null cause = last attempt died on a real error; earlier
            attempts may have applied ⇒ quiet ambiguity, cause preserved"
    (let [t (RaftRetryFailureException. a-request 4 (RetryPolicies/noRetry)
                                        (TimeoutIOException. "attempt timed out"))]
      (doseq [kind [:write :cas]]
        (let [v (outcome/classify kind t)]
          (is (= :info (:type v)) (str kind))
          (is (not (loud? v)) (str kind))
          (is (= :retry-failure (first (:error v))) (str kind))))
      (let [v (outcome/classify :read t)]
        (is (= :fail (:type v)))
        (is (not (loud? v)))))))

;; ---------------------------------------------------------------------------
;; Structural properties
;; ---------------------------------------------------------------------------

(def every-throwable
  "One instance of every exception the table names, plus unknowns."
  [not-leader
   leader-not-ready
   (ResourceUnavailableException. "x")
   (GroupMismatchException. "x")
   (StateMachineException. "x")
   (ReadException. "x")
   (ReadIndexException. "x")
   (TimeoutIOException. "x")
   (IOException. "x")
   (AlreadyClosedException. "x")
   (InterruptedIOException. "x")
   (InterruptedException. "x")
   (TimeoutException. "x")
   (RaftRetryFailureException. a-request 1 (RetryPolicies/noRetry) nil)
   (RaftRetryFailureException. a-request 2 (RetryPolicies/noRetry) (IOException. "y"))
   (RuntimeException. "x")
   (Error. "x")])

(deftest reads-are-never-info
  (testing "for every throwable and every reply shape, a read is :ok or :fail"
    (doseq [t every-throwable]
      (is (contains? #{:ok :fail} (:type (outcome/classify :read t)))
          (str (class t))))
    (doseq [reply ["OK" "VAL 1" "ABSENT" "MISMATCH 2" "ERR x" "junk"]]
      (is (contains? #{:ok :fail} (:type (outcome/classify :read reply)))
          reply))))

(deftest writes-never-fail-on-ambiguity
  (testing "no throwable that can follow an append ever maps a write to :fail
            unless the table names it as definite"
    (doseq [t [(TimeoutIOException. "x") (IOException. "x")
               (AlreadyClosedException. "x") (InterruptedIOException. "x")
               (TimeoutException. "x") (RuntimeException. "x")]]
      (is (= :info (:type (outcome/classify :write t))) (str (class t))))))

(deftest unwraps-future-wrappers
  (testing "ExecutionException/CompletionException from the harness future
            are unwrapped before dispatch"
    (is (= {:type :info :error :not-leader}
           (outcome/classify :write (ExecutionException. not-leader))))
    (is (= {:type :info :error :timeout}
           (outcome/classify :write
                             (CompletionException.
                               (TimeoutIOException. "rpc timeout")))))
    (is (= {:type :info :error :not-leader}
           (outcome/classify :write
                             (ExecutionException.
                               (CompletionException. not-leader)))))))

(deftest classify-bang-logs-and-strips-loud
  (testing "classify! returns the verdict without the ::loud key"
    (let [v (outcome/classify! :write (RuntimeException. "surprise"))]
      (is (= :info (:type v)))
      (is (not (loud? v))))
    (is (= {:type :ok} (outcome/classify! :write "OK")))))

(deftest rejects-unknown-op-kind
  (is (thrown? AssertionError (outcome/classify :increment "OK"))))

(deftest row-add-replies
  ;; Job 09: ADD replies VAL <total-after-this-apply>; the verdict keeps
  ;; the total under :observed (the checker sums deltas from op :value;
  ;; a deduplicated retry reports the CACHED original total).
  (testing "VAL is the only legal ADD reply"
    (is (= {:type :ok :observed 7} (outcome/classify :add "VAL 7")))
    (is (= {:type :ok :observed -2} (outcome/classify :add "VAL -2"))))
  (testing "every other reply shape is a protocol violation, loudly"
    (doseq [reply ["OK" "ABSENT" "MISMATCH 2" "junk"]]
      (let [v (outcome/classify :add reply)]
        (is (= :fail (:type v)) reply)
        (is (loud? v) reply))))
  (testing "ADD is write-kind: ambiguity rows give :info"
    (is (= {:type :info :error :not-leader} (outcome/classify :add not-leader)))
    (is (= {:type :info :error :harness-timeout}
           (outcome/classify :add (TimeoutException. "x"))))))
