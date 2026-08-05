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
  - retry policy: bounded fixed-sleep same-callId retries (DESIGN 2.3 and
    PLAN Q3 as amended 2026-08-05 — see open-raft-client for the
    soundness argument and the history);
  - ops {:f :read} / {:f :write} / {:f :cas} map 1:1 onto the DESIGN 1.4
    wire protocol (GET / PUT / CAS); writes travel client.io().send,
    reads client.io().sendReadOnly — leader-routed by default, or
    follower-targeted via sendReadOnly(msg, peerId) when the test runs
    --reads follower|mixed (M2: the linearizable follower-read path);
  - every invocation is wrapped in a harness-side timeout
    (`invoke-timeout-ms`, sitting above the library's 3 s default client
    rpc timeout), classified :info for writes / :fail for reads;
  - op :value comes through jepsen.independent tuples [k v]; keys are
    stringified as-is onto the wire.

  Also here (M2): the admin interop the snapshot-churn and transfer
  nemeses use — per-server snapshot creation and leadership transfer —
  because this namespace owns all RaftClient construction and calls."
  (:require [jepsen.client :as jc]
            [jepsen.independent :as independent]
            [ratis-jepsen.env-contract :as env]
            [ratis-jepsen.outcome :as outcome])
  (:import (java.util.concurrent TimeoutException TimeUnit)
           (org.apache.ratis.client RaftClient)
           (org.apache.ratis.conf RaftProperties)
           (org.apache.ratis.protocol Message RaftClientReply RaftGroup
                                      RaftGroupId RaftPeer RaftPeerId)
           (org.apache.ratis.retry RetryPolicies)
           (org.apache.ratis.util TimeDuration)))

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

(defn node-addresses
  "The id→host:port map for the given node names at the contract raft
  port — the group spec every client (worker and nemesis-admin alike)
  is built from."
  [nodes]
  (into {} (map (fn [n] [n (str n ":" env/raft-port)])) nodes))

(defn follower-candidates
  "The peers a follower-targeted read may go to: everyone except the
  believed leader — or every peer when the leader is unknown (nil) or
  not in the peer list. Pure; the belief comes from
  RaftClient.getLeaderId, which is best-effort by nature."
  [peers leader]
  (let [cands (vec (remove #{leader} peers))]
    (if (seq cands) cands (vec peers))))

(defn- pick-read-target
  "Where a :read goes: nil = leader-routed sendReadOnly (M0 behavior);
  a node name = follower-targeted sendReadOnly(msg, peerId). :follower
  always targets a non-leader; :mixed does so for half the reads."
  [^RaftClient client peers mode]
  (case mode
    :leader   nil
    :follower (rand-nth (follower-candidates
                          peers (some-> (.getLeaderId client) str)))
    :mixed    (when (< (rand) 0.5)
                (rand-nth (follower-candidates
                            peers (some-> (.getLeaderId client) str))))))

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

(def retry-attempts
  "Max attempts per invocation (bounded — jepsen still wants raw outcomes,
  never the library's retry-forever default)."
  4)

(def retry-sleep-ms
  "Fixed sleep between attempts. 4 fast-failing attempts cost ~0.8 s —
  well under invoke-timeout-ms; slow attempts (rpc timeouts) run into the
  harness deadline instead and surface as :info via the outcome map."
  200)

(defn open-raft-client
  "One RaftClient, default properties, bounded fixed-sleep retries
  (DESIGN 2.3 / PLAN Q3 as amended 2026-08-05).

  Originally noRetry, so every ambiguity surfaced raw — but Review 05
  (reviews/05-nemesis-breadth/05_report.md) proved that unsound in
  combination with the outcome map: a deposed leader completes
  appended-but-uncommitted writes with NotLeaderException and the entries
  can commit under its successor, so the \"definite\" NLE :fail produced a
  false-red on a healthy cluster. Bounded same-callId retries are the
  sound fix, per the reviewer's verification against ratis-client 3.2.2:
  BlockingImpl.send captures the callId once and every retry attempt
  rebuilds the request with that same (ClientId, callId), the server's
  retry cache deduplicates re-attempts, and a step-down-committed write's
  retry returns the cached success instead of a lying NLE — no
  double-apply is possible. Transients now mostly resolve to their true
  outcome; the exhausted residual is graded :info by the outcome map."
  ^RaftClient [id->address]
  (-> (RaftClient/newBuilder)
      (.setProperties (RaftProperties.))
      (.setRaftGroup (raft-group id->address))
      (.setRetryPolicy (RetryPolicies/retryUpToMaximumCountWithFixedSleep
                         retry-attempts
                         (TimeDuration/valueOf retry-sleep-ms
                                               TimeUnit/MILLISECONDS)))
      (.build)))

(defn- send-request
  "Sends one wire request through the client on the op's path and returns
  the decoded reply string. A non-nil read-target sends the read
  follower-targeted (still the linearizable path: the follower obtains a
  ReadIndex from the leader before answering)."
  ^String [^RaftClient client f ^String request read-target]
  (let [message (Message/valueOf request)
        ^RaftClientReply reply
        (cond
          (write-path? f) (.send (.io client) message)
          read-target     (.sendReadOnly (.io client) message
                                         (RaftPeerId/valueOf
                                           ^String read-target))
          :else           (.sendReadOnly (.io client) message))]
    (-> reply .getMessage .getContent .toStringUtf8)))

(defn- invoke-raw
  "Runs one invocation under the harness-side deadline. Returns the reply
  string, or throws: the raw failure (unwrapped by the outcome map), or
  java.util.concurrent.TimeoutException when the deadline fires first."
  [^RaftClient client {:keys [f] :as op} read-target]
  (let [request (op->request op)
        fut     (future (send-request client f request read-target))
        result  (deref fut invoke-timeout-ms ::timed-out)]
    (if (= result ::timed-out)
      (do (future-cancel fut)
          (throw (TimeoutException.
                   (str "harness-side timeout after " invoke-timeout-ms
                        " ms: " request))))
      result)))

;; ---------------------------------------------------------------------------
;; Admin interop (M2 nemeses: snapshot churn, leadership transfer)
;; ---------------------------------------------------------------------------

(def admin-timeout-ms
  "Request timeout for nemesis admin calls (snapshot create, leadership
  transfer). These run on the nemesis thread with no harness deadline —
  a slow call just stretches its fault segment."
  10000)

(defn snapshot-create!
  "Asks `node` to take a snapshot now (SnapshotManagementApi.create,
  routed per-server as the API requires). Returns the reply's success
  flag + index for the history record — NEVER trusted as proof a
  snapshot exists (BACKLOG item 5: the API can report success while
  takeSnapshot failed); the install-snapshot evidence checker judges
  from logs. Throws on transport/timeout errors — callers record and
  tolerate."
  [^RaftClient client node]
  (let [^RaftClientReply reply (-> client
                                   (.getSnapshotManagementApi
                                     (RaftPeerId/valueOf ^String node))
                                   (.create admin-timeout-ms))]
    {:success? (.isSuccess reply)
     :index    (.getLogIndex reply)}))

(defn transfer-leadership!
  "Asks the group to transfer leadership to `node`
  (AdminApi.transferLeadership). Returns the reply's success flag;
  throws on failure — TransferLeadershipException on a timed-out or
  refused handover is a LEGAL outcome the caller records, not a nemesis
  crash."
  [^RaftClient client node]
  (let [^RaftClientReply reply (-> (.admin client)
                                   (.transferLeadership
                                     (RaftPeerId/valueOf ^String node)
                                     admin-timeout-ms))]
    {:success? (.isSuccess reply)}))

;; ---------------------------------------------------------------------------
;; Jepsen client
;; ---------------------------------------------------------------------------

(defrecord RatisKvClient [group-spec raft-client reads-mode peers]
  jc/Client
  (open! [this test _node]
    ;; One RaftClient per Jepsen process; the group is fixed, so every
    ;; client may talk to any node (the library routes to the leader).
    ;; The read mode comes from the test map (--reads, default leader);
    ;; follower targets are drawn from the client's own peer set.
    (assoc this
           :raft-client (open-raft-client group-spec)
           :reads-mode  (keyword (or (:reads test) "leader"))
           :peers       (vec (keys group-spec))))

  (setup! [_this _test])

  (invoke! [_this _test op]
    (let [target  (when (= :read (:f op))
                    (pick-read-target raft-client peers reads-mode))
          op      (cond-> op target (assoc :read-via target))
          outcome (try (invoke-raw raft-client op target)
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
   (client (node-addresses env/initial-voters)))
  ([group-spec]
   (map->RatisKvClient {:group-spec group-spec})))
