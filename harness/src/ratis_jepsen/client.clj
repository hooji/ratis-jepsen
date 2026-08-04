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

(ns ratis-jepsen.client
  "Jepsen client for ratis-kv via direct RaftClient interop (DESIGN 2.3,
  PLAN Q1/Q2/Q3):

  - one RaftClient per Jepsen process (open! builds it, close! closes it) —
    one worker = one ClientId = one callId stream;
  - retry policy noRetry, so every ambiguity surfaces to the outcome map
    instead of being laundered by the library;
  - ops {:f :read} / {:f :write} / {:f :cas} map 1:1 onto the DESIGN 1.4
    wire protocol (GET / PUT / CAS); writes travel client.io().send,
    reads client.io().sendReadOnly;
  - every invocation is wrapped in a harness-side timeout
    (`invoke-timeout-ms`, sitting above the library's 3 s default client
    rpc timeout), classified :info for writes / :fail for reads;
  - op :value comes through jepsen.independent tuples [k v]; keys are
    stringified as-is onto the wire."
  (:require [jepsen.client :as jc]
            [jepsen.independent :as independent]
            [ratis-jepsen.env-contract :as env]
            [ratis-jepsen.outcome :as outcome])
  (:import (java.util.concurrent TimeoutException)
           (org.apache.ratis.client RaftClient)
           (org.apache.ratis.conf RaftProperties)
           (org.apache.ratis.protocol Message RaftClientReply RaftGroup
                                      RaftGroupId RaftPeer)
           (org.apache.ratis.retry RetryPolicies)))

(def invoke-timeout-ms
  "Harness-side deadline on every invocation: 5 s, above the library's 3 s
  default client rpc timeout (DESIGN 1.3) so the library's own timeout is
  the one that normally fires."
  5000)

;; ---------------------------------------------------------------------------
;; op <-> wire mapping (pure)
;; ---------------------------------------------------------------------------

(defn op->request
  "Maps a Jepsen op onto its DESIGN 1.4 wire request string. The op's
  :value is an independent tuple: [k _] for :read, [k v] for :write,
  [k [old new]] for :cas. Keys are stringified as-is."
  [{:keys [f value] :as op}]
  (let [[k v] value]
    (case f
      :read  (str "GET " k)
      :write (str "PUT " k " " v)
      :cas   (let [[old new] v]
               (str "CAS " k " " old " " new))
      (throw (IllegalArgumentException.
               (str "unknown op :f " (pr-str f) " in " (pr-str op)))))))

(defn write-path?
  "PUT and CAS travel client.io().send (raft log); GET travels
  client.io().sendReadOnly (ReadIndex)."
  [f]
  (outcome/write-kind? f))

(defn verdict->op
  "Merges an outcome-map verdict into the invoked op: :type always, :error
  and :current when present, and for a successful read the :value becomes
  (independent/tuple k read-value) so per-key checkers see it."
  [op verdict]
  (let [[k _] (:value op)
        op'   (assoc op :type (:type verdict))
        op'   (if (contains? verdict :error)
                (assoc op' :error (:error verdict))
                op')
        op'   (if (contains? verdict :current)
                (assoc op' :current (:current verdict))
                op')]
    (if (and (= :read (:f op)) (= :ok (:type verdict)))
      (assoc op' :value (independent/tuple k (:value verdict)))
      op')))

;; ---------------------------------------------------------------------------
;; RaftClient interop
;; ---------------------------------------------------------------------------

(defn raft-group
  "The SUT's raft group: the fixed group uuid (env-contract, copied from
  the SUT's Main.GROUP_UUID) over the given id→host:port peers."
  ^RaftGroup [id->address]
  (RaftGroup/valueOf
    (RaftGroupId/valueOf env/group-uuid)
    ^Iterable (mapv (fn [[id address]]
                      (-> (RaftPeer/newBuilder)
                          (.setId ^String id)
                          (.setAddress ^String address)
                          (.build)))
                    id->address)))

(defn open-raft-client
  "One RaftClient, default properties, noRetry (DESIGN 2.3)."
  ^RaftClient [id->address]
  (-> (RaftClient/newBuilder)
      (.setProperties (RaftProperties.))
      (.setRaftGroup (raft-group id->address))
      (.setRetryPolicy (RetryPolicies/noRetry))
      (.build)))

(defn- send-request
  "Sends one wire request through the client on the op's path and returns
  the decoded reply string."
  ^String [^RaftClient client f ^String request]
  (let [message (Message/valueOf request)
        ^RaftClientReply reply (if (write-path? f)
                                 (.send (.io client) message)
                                 (.sendReadOnly (.io client) message))]
    (-> reply .getMessage .getContent .toStringUtf8)))

(defn- invoke-raw
  "Runs one invocation under the harness-side deadline. Returns the reply
  string, or throws: the raw failure (unwrapped by the outcome map), or
  java.util.concurrent.TimeoutException when the deadline fires first."
  [^RaftClient client {:keys [f] :as op}]
  (let [request (op->request op)
        fut     (future (send-request client f request))
        result  (deref fut invoke-timeout-ms ::timed-out)]
    (if (= result ::timed-out)
      (do (future-cancel fut)
          (throw (TimeoutException.
                   (str "harness-side timeout after " invoke-timeout-ms
                        " ms: " request))))
      result)))

;; ---------------------------------------------------------------------------
;; Jepsen client
;; ---------------------------------------------------------------------------

(defrecord RatisKvClient [group-spec raft-client]
  jc/Client
  (open! [this _test _node]
    ;; One RaftClient per Jepsen process; the group is fixed, so every
    ;; client may talk to any node (the library routes to the leader).
    (assoc this :raft-client (open-raft-client group-spec)))

  (setup! [_this _test])

  (invoke! [_this _test op]
    (let [outcome (try (invoke-raw raft-client op)
                       (catch Throwable t t))]
      (verdict->op op (outcome/classify! (:f op) outcome))))

  (teardown! [_this _test])

  (close! [_this _test]
    (.close ^RaftClient raft-client)))

(defn client
  "A fresh, unopened Jepsen client. With no arguments it targets the
  deployment contract's initial voters on the contract raft port; tests
  pass an explicit id→host:port map."
  ([]
   (client (into {} (map (fn [n] [n (str n ":" env/raft-port)]))
                 env/initial-voters)))
  ([group-spec]
   (map->RatisKvClient {:group-spec group-spec})))
