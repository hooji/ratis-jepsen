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

(ns ratis-jepsen.client-test
  "Unit tests for the op↔wire mapping. The round-trip half decodes the
  harness's request strings with the SUT's own codec (ratis-kv is a
  test-only dep) and parses SUT-encoded replies with the harness's outcome
  parser, so harness and SUT provably speak the same protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [jepsen.independent :as independent]
            [ratis-jepsen.client :as client]
            [ratis-jepsen.outcome :as outcome])
  (:import (ratis.jepsen.kv KvCodec
                            KvCodec$Request$Put
                            KvCodec$Request$Cas
                            KvCodec$Request$Get
                            KvCodec$Reply$Ok
                            KvCodec$Reply$Val
                            KvCodec$Reply$Absent
                            KvCodec$Reply$Mismatch
                            KvCodec$Reply$Err)))

;; ---------------------------------------------------------------------------
;; op → wire
;; ---------------------------------------------------------------------------

(deftest op->request-forms
  (testing "the three op forms, with plain-vector tuples"
    (is (= "GET k1" (client/op->request {:f :read :value ["k1" nil]})))
    (is (= "PUT k1 42" (client/op->request {:f :write :value ["k1" 42]})))
    (is (= "CAS k1 1 2" (client/op->request {:f :cas :value ["k1" [1 2]]}))))
  (testing "jepsen.independent tuples destructure the same way"
    (is (= "GET 5" (client/op->request {:f :read :value (independent/tuple 5 nil)})))
    (is (= "PUT 5 -3" (client/op->request {:f :write :value (independent/tuple 5 -3)})))
    (is (= "CAS 5 7 9" (client/op->request {:f :cas :value (independent/tuple 5 [7 9])}))))
  (testing "keys are stringified as-is"
    (is (= "GET register_0" (client/op->request {:f :read :value ["register_0" nil]}))))
  (testing "unknown :f is a harness bug and throws"
    (is (thrown? IllegalArgumentException
                 (client/op->request {:f :increment :value ["k" 1]})))))

(deftest op-paths
  (testing "PUT/CAS travel the write path, GET the read path"
    (is (client/write-path? :write))
    (is (client/write-path? :cas))
    (is (not (client/write-path? :read)))))

;; ---------------------------------------------------------------------------
;; Round-trips through the SUT's own codec
;; ---------------------------------------------------------------------------

(deftest requests-decode-with-sut-codec
  (testing "harness-encoded requests decode to the intended SUT commands"
    (let [put (KvCodec/decodeRequest (client/op->request {:f :write :value ["k1" 42]}))]
      (is (instance? KvCodec$Request$Put put))
      (is (= "k1" (.key ^KvCodec$Request$Put put)))
      (is (= 42 (.value ^KvCodec$Request$Put put))))
    (let [cas (KvCodec/decodeRequest (client/op->request {:f :cas :value ["k1" [1 2]]}))]
      (is (instance? KvCodec$Request$Cas cas))
      (is (= "k1" (.key ^KvCodec$Request$Cas cas)))
      (is (= 1 (.expect ^KvCodec$Request$Cas cas)))
      (is (= 2 (.update ^KvCodec$Request$Cas cas))))
    (let [get' (KvCodec/decodeRequest (client/op->request {:f :read :value ["k1" nil]}))]
      (is (instance? KvCodec$Request$Get get'))
      (is (= "k1" (.key ^KvCodec$Request$Get get'))))))

(deftest replies-from-sut-codec-parse
  (testing "SUT-encoded replies parse to what the outcome map expects"
    (is (= [:ok] (outcome/parse-reply (KvCodec/encodeReply (KvCodec$Reply$Ok.)))))
    (is (= [:val 42] (outcome/parse-reply (KvCodec/encodeReply (KvCodec$Reply$Val. 42)))))
    (is (= [:val -7] (outcome/parse-reply (KvCodec/encodeReply (KvCodec$Reply$Val. -7)))))
    (is (= [:absent] (outcome/parse-reply (KvCodec/encodeReply (KvCodec$Reply$Absent.)))))
    (is (= [:mismatch 3] (outcome/parse-reply (KvCodec/encodeReply (KvCodec$Reply$Mismatch. 3)))))
    (is (= [:err "bad input"] (outcome/parse-reply (KvCodec/encodeReply (KvCodec$Reply$Err. "bad input")))))))

(deftest parse-reply-strictness
  (testing "near-miss replies do not parse as successes"
    (is (= [:unparseable "VAL"] (outcome/parse-reply "VAL")))
    (is (= [:unparseable "VAL x"] (outcome/parse-reply "VAL x")))
    (is (= [:unparseable "VAL 1 2"] (outcome/parse-reply "VAL 1 2")))
    (is (= [:unparseable "MISMATCH"] (outcome/parse-reply "MISMATCH")))
    (is (= [:unparseable "ok"] (outcome/parse-reply "ok")))
    (is (= [:unparseable "OK "] (outcome/parse-reply "OK ")))
    (is (= [:unparseable ""] (outcome/parse-reply "")))
    (is (= [:unparseable nil] (outcome/parse-reply nil)))
    (is (= [:unparseable "ERR "] (outcome/parse-reply "ERR ")))))

;; ---------------------------------------------------------------------------
;; verdict → op
;; ---------------------------------------------------------------------------

(deftest verdict->op-merging
  (testing "a successful read rewraps :value as an independent tuple"
    (let [op  {:type :invoke :f :read :value (independent/tuple "k1" nil)}
          op' (client/verdict->op op {:type :ok :value 42})]
      (is (= :ok (:type op')))
      (is (independent/tuple? (:value op')))
      (is (= ["k1" 42] [(key (:value op')) (val (:value op'))]))))
  (testing "a read of a missing key carries nil"
    (let [op' (client/verdict->op {:type :invoke :f :read :value ["k1" nil]}
                                  {:type :ok :value nil})]
      (is (= :ok (:type op')))
      (is (= nil (val (:value op'))))))
  (testing "writes keep their invoked :value"
    (let [op  {:type :invoke :f :write :value (independent/tuple "k1" 42)}
          op' (client/verdict->op op {:type :info :error :timeout})]
      (is (= :info (:type op')))
      (is (= :timeout (:error op')))
      (is (identical? (:value op) (:value op')))))
  (testing "cas precondition failure carries :error and :current"
    (let [op' (client/verdict->op {:type :invoke :f :cas :value ["k1" [1 2]]}
                                  {:type :fail :error :precondition :current 9})]
      (is (= :fail (:type op')))
      (is (= :precondition (:error op')))
      (is (= 9 (:current op')))
      (is (= ["k1" [1 2]] (:value op')))))
  (testing "a failed read keeps its invoked :value untouched"
    (let [op  {:type :invoke :f :read :value (independent/tuple "k1" nil)}
          op' (client/verdict->op op {:type :fail :error :not-leader})]
      (is (= :fail (:type op')))
      (is (identical? (:value op) (:value op'))))))

;; ---------------------------------------------------------------------------
;; Follower-read targeting (Job 07) — the pure candidate selection
;; ---------------------------------------------------------------------------

(deftest follower-candidates-selection
  (let [peers ["n1" "n2" "n3" "n4" "n5"]]
    (testing "the believed leader is excluded"
      (is (= ["n1" "n2" "n4" "n5"] (client/follower-candidates peers "n3"))))
    (testing "unknown leader (nil): all peers are candidates"
      (is (= peers (client/follower-candidates peers nil))))
    (testing "leader not in the peer list: all peers are candidates"
      (is (= peers (client/follower-candidates peers "n9"))))
    (testing "degenerate single-peer group: never empty"
      (is (= ["n1"] (client/follower-candidates ["n1"] "n1"))))))
