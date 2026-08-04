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

(ns ratis-jepsen.integration-test
  "In-JVM integration test (no Docker, no ssh): boots 3 real ratis-kv
  servers on 127.0.0.1 fixed ports through the SUT's own CLI code path
  (ServerOptions.parse → Main.buildServer — Job 01's smoke test is the
  precedent), then drives them through the harness's jepsen client and
  outcome map, proving the four classifications end to end:

    write          ⇒ :ok
    read           ⇒ :ok with the value
    cas mismatch   ⇒ :fail :precondition
    write with all servers down ⇒ :info (the ambiguous-outcome path,
                     via whatever real exception the 3.2.2 client raises)"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jepsen.client :as jc]
            [ratis-jepsen.client :as client])
  (:import (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Comparator)
           (org.apache.ratis.server RaftServer)
           (ratis.jepsen.kv Main ServerOptions)))

;; Fixed ports of our choosing (Job 03 brief) — high and unremarkable to
;; avoid collisions with anything a dev box or CI runner typically runs.
(def ids ["n1" "n2" "n3"])
(def ports [26631 26632 26633])

(def group-spec
  "id → 127.0.0.1:port, the client's view of the cluster."
  (zipmap ids (map #(str "127.0.0.1:" %) ports)))

(def peers-arg
  (str/join "," (map (fn [id port] (str id "=127.0.0.1:" port)) ids ports)))

(defn- build-server
  "Assembles one ratis-kv server through the very same code path the CLI
  uses, storage under base/<id>."
  ^RaftServer [^Path base id]
  (-> (into-array String ["--id" id
                          "--peers" peers-arg
                          "--storage" (str (.resolve base id))])
      (ServerOptions/parse)
      (Main/buildServer)))

(defn- invoke-until
  "Invokes op through the jepsen client until (pred op') holds, retrying
  through the transient failures of a cluster that has not elected a
  leader yet. Returns the first satisfying completed op; throws at the
  deadline. Every attempt still travels the full client+outcome path."
  [c op pred deadline-ms]
  (let [deadline (+ (System/nanoTime) (* deadline-ms 1000000))]
    (loop [attempts []]
      (let [op' (jc/invoke! c {} op)]
        (cond
          (pred op') op'

          (< (System/nanoTime) deadline)
          (do (Thread/sleep 250)
              (recur (conj attempts [(:type op') (:error op')])))

          :else
          (throw (ex-info (str "no satisfying outcome for " (:f op)
                               " within " deadline-ms " ms")
                          {:op op :attempts attempts :last op'})))))))

(defn- delete-recursively! [^Path base]
  (with-open [walk (Files/walk base (make-array java.nio.file.FileVisitOption 0))]
    (doseq [^Path p (-> walk (.sorted (Comparator/reverseOrder)) .iterator iterator-seq)]
      (Files/deleteIfExists p))))

(deftest in-jvm-cluster-proves-the-four-classifications
  (let [base    (Files/createTempDirectory "ratis-jepsen-it"
                                           (make-array FileAttribute 0))
        servers (mapv #(build-server base %) ids)]
    (try
      (run! #(.start ^RaftServer %) servers)
      (let [c (jc/open! (client/client group-spec) {} (first ids))]
        (try
          (testing "write ⇒ :ok"
            (let [op' (invoke-until c
                                    {:type :invoke :f :write :value ["k1" 42]}
                                    #(= :ok (:type %))
                                    60000)]
              (is (= :ok (:type op')))))

          (testing "read ⇒ :ok with the written value"
            (let [op' (invoke-until c
                                    {:type :invoke :f :read :value ["k1" nil]}
                                    #(= :ok (:type %))
                                    30000)]
              (is (= :ok (:type op')))
              (is (= 42 (val (:value op'))))
              (is (= "k1" (key (:value op'))))))

          (testing "read of an absent key ⇒ :ok with nil"
            (let [op' (invoke-until c
                                    {:type :invoke :f :read :value ["nope" nil]}
                                    #(= :ok (:type %))
                                    30000)]
              (is (nil? (val (:value op'))))))

          (testing "cas mismatch ⇒ :fail :precondition"
            (let [op' (invoke-until c
                                    {:type :invoke :f :cas :value ["k1" [999 7]]}
                                    #(and (= :fail (:type %))
                                          (= :precondition (:error %)))
                                    30000)]
              (is (= :fail (:type op')))
              (is (= :precondition (:error op')))
              ;; the server reports what the register actually held
              (is (= 42 (:current op')))))

          (testing "cas whose precondition holds ⇒ :ok"
            (let [op' (invoke-until c
                                    {:type :invoke :f :cas :value ["k1" [42 43]]}
                                    #(= :ok (:type %))
                                    30000)]
              (is (= :ok (:type op')))))

          (testing "all servers down: a write classifies :info (ambiguous), never :ok/:fail"
            (run! #(.close ^RaftServer %) servers)
            (let [op' (jc/invoke! c {} {:type :invoke :f :write :value ["k1" 99]})]
              (is (= :info (:type op'))
                  (str "expected :info, got " (:type op')
                       " with :error " (:error op')))))

          (finally
            (jc/close! c {}))))
      (finally
        (run! #(try (.close ^RaftServer %) (catch Exception _)) servers)
        (try (delete-recursively! base) (catch Exception _))))))
