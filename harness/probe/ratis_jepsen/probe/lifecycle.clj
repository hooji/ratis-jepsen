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

(ns ratis-jepsen.probe.lifecycle
  "The Job 12 library probe for BACKLOG items 7 and 8, run against
  whichever Ratis version is on the classpath (env/run.sh's -Sdeps
  override selects it — same mechanism as the harness proper).

  The subject is the LIBRARY, not our SUT: every server here runs a
  deliberately NAIVE state machine — the upstream-CounterStateMachine
  template that extends BaseStateMachine and does NOT override pause()
  or manage the lifecycle — because the shipped SUT has carried its own
  lifecycle discipline since Job 08 and would mask the base-class trap
  this probe exists to measure.

  Three phases, each printing machine-greppable `PROBE key=value` lines:

  A. base-pause: boot a 1-node group on the naive SM, then perform the
     exact call the install path makes — sm.pause() — and record the
     lifecycle state before and after. BACKLOG 7's primary trap needs
     pause() to reach PAUSED (StateMachineUpdater.reload() asserts it);
     an unchanged state reproduces the trap at this version.

  B. group-info-conf: BACKLOG 8's one-call check — a real over-the-wire
     GroupManagementApi.info against the same group; record whether
     GroupInfoReply.getConf() arrives populated (3.2.2: always empty —
     toGroupInfoReplyProto never set the field).

  C. install-chain: the full live sequence on a 3-node group — stop a
     follower, write past the snapshot threshold, bump the term
     (transferLeadership closes open segments), snapshot-and-purge the
     live pair, restart the follower so install-snapshot is its only way
     back — then record whether the receiving division survives its
     install (division isAlive + lifecycle + a targeted read) and how
     hard the leader hammers it meanwhile (the stdout log carries the
     GrpcLogAppender re-initiations; the wrapper script counts them).

  Run it: env/run.sh probe [--ratis-version V]."
  (:require [clojure.string :as str])
  (:import (java.io DataInputStream DataOutputStream File)
           (java.net ServerSocket)
           (java.nio.file Files)
           (java.util.concurrent CompletableFuture TimeUnit)
           (org.apache.ratis.client RaftClient)
           (org.apache.ratis.conf RaftProperties)
           (org.apache.ratis.protocol Message RaftGroup RaftGroupId RaftPeer
                                      RaftPeerId)
           (org.apache.ratis.retry RetryPolicies)
           (org.apache.ratis.server RaftServer RaftServerConfigKeys
                                    RaftServerConfigKeys$Log
                                    RaftServerConfigKeys$Rpc
                                    RaftServerConfigKeys$Snapshot)
           (org.apache.ratis.server.protocol TermIndex)
           (org.apache.ratis.server.storage RaftStorage$StartupOption)
           (org.apache.ratis.statemachine.impl BaseStateMachine
                                               SimpleStateMachineStorage
                                               SingleFileSnapshotInfo)
           (org.apache.ratis.server.storage FileInfo)
           (org.apache.ratis.util LifeCycle$State MD5FileUtil TimeDuration)))

(def group-id
  "A fixed group id for the probe's throwaway clusters."
  (RaftGroupId/valueOf (java.util.UUID/fromString
                         "3f9c2f3e-12aa-4bfb-9d3c-0be9a2c40001")))

(defn probe-line
  "One machine-greppable result line."
  [& kvs]
  (println (str "PROBE " (str/join " " (map (fn [[k v]] (str (name k) "=" v))
                                            (partition 2 kvs))))))

;; ---------------------------------------------------------------------------
;; The naive state machine: upstream's CounterStateMachine template — a
;; single long, real single-file snapshots via SimpleStateMachineStorage,
;; and NO lifecycle management anywhere. pause() is deliberately NOT
;; overridden: inheriting the version's BaseStateMachine.pause() verbatim
;; is the point of the probe.
;; ---------------------------------------------------------------------------

(defn naive-state-machine
  "Returns the proxy. State: an atom holding a long; ADD n adds, GET
  reads. updateLastAppliedTermIndex is 'overridden' pass-through so the
  (protected) super method becomes callable from applyTransaction."
  []
  (let [storage (SimpleStateMachineStorage.)
        state   (atom 0)
        load!   (fn [^SingleFileSnapshotInfo snapshot]
                  (when snapshot
                    (let [^File f (-> snapshot .getFile .getPath .toFile)]
                      (when (.exists f)
                        (with-open [in (DataInputStream.
                                         (Files/newInputStream
                                           (.toPath f)
                                           (make-array java.nio.file.OpenOption 0)))]
                          (reset! state (.readLong in)))))))]
    (proxy [BaseStateMachine] []
      (initialize [server raft-group-id raft-storage]
        (proxy-super initialize server raft-group-id raft-storage)
        (.init storage raft-storage)
        (load! (.loadLatestSnapshot storage)))

      (reinitialize []
        (load! (.loadLatestSnapshot storage)))

      (getStateMachineStorage [] storage)

      ;; Pass-through override with BOTH overloads: proxy routes every
      ;; overload of a name to the one fn (the base class's own
      ;; notifyTermIndexUpdated forwards to the (long,long) arity), and
      ;; overriding makes the protected method callable from
      ;; applyTransaction below.
      (updateLastAppliedTermIndex
        ([ti]
         (proxy-super updateLastAppliedTermIndex ti))
        ([term index]
         (proxy-super updateLastAppliedTermIndex term index)))

      (applyTransaction [trx]
        (let [entry   (.getLogEntry trx)
              command (-> entry .getStateMachineLogEntry .getLogData
                          .toStringUtf8)
              [op arg] (str/split command #" " 2)
              reply   (case op
                        "ADD" (swap! state + (Long/parseLong arg))
                        @state)]
          (.updateLastAppliedTermIndex ^Object this
                                       (TermIndex/valueOf (.getTerm entry)
                                                          (.getIndex entry)))
          (CompletableFuture/completedFuture
            (Message/valueOf (str "VAL " reply)))))

      (query [request]
        (CompletableFuture/completedFuture
          (Message/valueOf (str "VAL " @state))))

      (takeSnapshot []
        (let [^TermIndex applied (.getLastAppliedTermIndex ^Object this)
              value  @state
              ^File file (.getSnapshotFile storage (.getTerm applied)
                                           (.getIndex applied))]
          (with-open [out (DataOutputStream.
                            (Files/newOutputStream
                              (.toPath file)
                              (make-array java.nio.file.OpenOption 0)))]
            (.writeLong out value))
          (let [md5  (MD5FileUtil/computeAndSaveMd5ForFile file)
                info (FileInfo. (.toPath file) md5)]
            (.updateLatestSnapshot storage
                                   (SingleFileSnapshotInfo. info applied)))
          (.getIndex applied))))))

;; ---------------------------------------------------------------------------
;; Cluster plumbing (MiniCluster's shape, RaftServer built directly so the
;; state machine is ours)
;; ---------------------------------------------------------------------------

(defn allocate-ports [n]
  (let [sockets (vec (repeatedly n #(doto (ServerSocket. 0)
                                      (.setReuseAddress true))))
        ports   (mapv #(.getLocalPort ^ServerSocket %) sockets)]
    (run! #(.close ^ServerSocket %) sockets)
    ports))

(defn raft-group ^RaftGroup [id->address]
  (RaftGroup/valueOf
    group-id
    ^Iterable (mapv (fn [[id address]]
                      (-> (RaftPeer/newBuilder)
                          (.setId ^String id)
                          (.setAddress ^String address)
                          (.build)))
                    id->address)))

(defn server-properties
  "Aggressive snapshot/purge so the install path is reachable at small
  indexes: auto-snapshot every 15 entries, purge gap 8, purge up to the
  snapshot index. Election timeouts left at in-JVM-friendly defaults."
  [storage-dir port]
  (let [p (RaftProperties.)]
    (RaftServerConfigKeys/setStorageDir p [(File. ^String storage-dir)])
    (RaftServerConfigKeys$Snapshot/setAutoTriggerEnabled p true)
    (RaftServerConfigKeys$Snapshot/setAutoTriggerThreshold p 15)
    (RaftServerConfigKeys$Log/setPurgeUptoSnapshotIndex p true)
    (RaftServerConfigKeys$Log/setPurgeGap p (int 8))
    (org.apache.ratis.grpc.GrpcConfigKeys$Server/setPort p (int port))
    p))

(defn build-server ^RaftServer [id id->address storage-dir]
  (let [port (-> (get id->address id) (str/split #":") second Long/parseLong)]
    (-> (RaftServer/newBuilder)
        (.setServerId (RaftPeerId/valueOf ^String id))
        (.setStateMachine (naive-state-machine))
        (.setProperties (server-properties storage-dir port))
        (.setOption RaftStorage$StartupOption/RECOVER)
        (.setGroup (raft-group id->address))
        (.build))))

(defn new-client ^RaftClient [id->address]
  (-> (RaftClient/newBuilder)
      (.setProperties (RaftProperties.))
      (.setRaftGroup (raft-group id->address))
      (.setRetryPolicy (RetryPolicies/retryUpToMaximumCountWithFixedSleep
                         150 (TimeDuration/valueOf 200 TimeUnit/MILLISECONDS)))
      (.build)))

(defn send! ^String [^RaftClient client ^String request]
  (-> (.io client) (.send (Message/valueOf request))
      .getMessage .getContent .toStringUtf8))

(defn targeted-read
  "One no-retry linearizable read routed at `node`; returns the reply
  string or the exception's class simple-name."
  [id->address node]
  (with-open [^RaftClient c (-> (RaftClient/newBuilder)
                                (.setProperties (RaftProperties.))
                                (.setRaftGroup (raft-group id->address))
                                (.setRetryPolicy (RetryPolicies/noRetry))
                                (.build))]
    (try (-> (.io c)
             (.sendReadOnly (Message/valueOf "GET")
                            (RaftPeerId/valueOf ^String node))
             .getMessage .getContent .toStringUtf8)
         (catch Throwable t
           (str "EX:" (.getSimpleName (class t))
                (when-let [c (.getCause t)]
                  (str "/" (.getSimpleName (class c)))))))))

(defn await-writes!
  "ADDs `n` ones through the retrying client (rides out elections)."
  [^RaftClient client n]
  (dotimes [_ n] (send! client "ADD 1")))

(defn division [^RaftServer server]
  (.getDivision server group-id))

(defn division-report [^RaftServer server]
  (try
    (let [div  (division server)
          info (.getInfo div)
          sm   (.getStateMachine div)]
      {:alive     (.isAlive info)
       :division  (str (.getLifeCycleState info))
       :sm        (str (.getLifeCycleState ^BaseStateMachine sm))
       :applied   (str (.getLastAppliedTermIndex ^BaseStateMachine sm))})
    (catch Throwable t {:error (.getSimpleName (class t))})))

;; ---------------------------------------------------------------------------
;; Phases
;; ---------------------------------------------------------------------------

(defn classpath-ratis-version []
  (or (some #(second (re-find #"ratis-server-([0-9][^/\\]*)\.jar$" %))
            (str/split (System/getProperty "java.class.path")
                       (re-pattern (java.util.regex.Pattern/quote
                                     (System/getProperty "path.separator")))))
      "unknown"))

(defn phase-a-and-b!
  "1-node group on the naive SM: the direct pause probe (A) and the
  GroupInfoReply conf check (B, over the real wire)."
  [tmp]
  (let [[port]  (allocate-ports 1)
        spec    {"p1" (str "127.0.0.1:" port)}
        server  (build-server "p1" spec (str tmp "/p1"))]
    (.start server)
    (with-open [client (new-client spec)]
      (await-writes! client 3)
      (let [sm     (.getStateMachine (division server))
            before (str (.getLifeCycleState ^BaseStateMachine sm))
            _      (.pause ^BaseStateMachine sm)
            after  (str (.getLifeCycleState ^BaseStateMachine sm))]
        (probe-line :phase "A"
                    :sm-state-before-pause before
                    :sm-state-after-pause after
                    :base-pause-reaches-paused
                    (= after (str LifeCycle$State/PAUSED))))
      ;; B: one info call, over gRPC (loopback, but the full serializer
      ;; path — the 3.2.2 drop happens in toGroupInfoReplyProto).
      (let [reply (-> client
                      (.getGroupManagementApi (RaftPeerId/valueOf "p1"))
                      (.info group-id))
            conf  (.getConf reply)]
        (probe-line :phase "B"
                    :conf-present (.isPresent conf)
                    :conf (if (.isPresent conf)
                            (pr-str (str/replace (str (.get conf)) #"\s+" " "))
                            "empty"))))
    (.close server)))

(defn phase-c!
  "3-node group: the live install chain against the naive SM."
  [tmp]
  (let [ports   (allocate-ports 3)
        ids     ["c1" "c2" "c3"]
        spec    (into {} (map (fn [id port] [id (str "127.0.0.1:" port)])
                              ids ports))
        servers (into {} (map (fn [id]
                                [id (doto (build-server id spec
                                                        (str tmp "/" id))
                                      (.start))])
                              ids))]
    (with-open [client (new-client spec)]
      (await-writes! client 30)
      ;; who leads? kill a follower.
      (let [leader   (loop [n 60]
                       (or (some (fn [[id s]]
                                   (when (-> s division .getInfo .isLeader) id))
                                 servers)
                           (when (pos? n) (Thread/sleep 500) (recur (dec n)))))
            follower (first (remove #{leader} ids))
            other    (first (remove #{leader follower} ids))]
        (probe-line :phase "C" :leader leader :stopped-follower follower)
        (.close ^RaftServer (servers follower))
        (await-writes! client 40)
        ;; term bump: hand leadership to the other live node (closes open
        ;; segments so the purge below is real — Job 07's mechanism)
        (let [transfer (try (-> client .admin
                                (.transferLeadership
                                  (RaftPeerId/valueOf ^String other) 10000)
                                .isSuccess)
                            (catch Throwable t
                              (str "EX:" (.getSimpleName (class t)))))]
          (probe-line :phase "C" :transfer-to other :transfer-ok transfer))
        (await-writes! client 10)
        (doseq [id (remove #{follower} ids)]
          (let [r (try (-> client
                           (.getSnapshotManagementApi
                             (RaftPeerId/valueOf ^String id))
                           (.create 10000)
                           .getLogIndex)
                       (catch Throwable t
                         (str "EX:" (.getSimpleName (class t)))))]
            (probe-line :phase "C" :snapshot-on id :snapshot-index r)))
        (await-writes! client 5)
        ;; restart the follower on its recovered storage: with the live
        ;; pair's logs purged past it, install-snapshot is its only way
        ;; back
        (let [restarted (doto (build-server follower spec
                                            (str tmp "/" follower))
                          (.start))]
          (probe-line :phase "C" :restarted follower)
          ;; watch the division across the install window
          (dotimes [i 6]
            (Thread/sleep 5000)
            (let [r (division-report restarted)]
              (probe-line :phase "C" :t (* 5 (inc i))
                          :follower-alive (:alive r)
                          :follower-division (:division r)
                          :follower-sm (:sm r)
                          :follower-applied (:applied r)
                          :error (:error r))))
          (let [r    (division-report restarted)
                read (targeted-read spec follower)]
            (probe-line :phase "C"
                        :install-outcome (if (:alive r) "survived" "died")
                        :follower-final (pr-str r)
                        :targeted-read read))
          (.close ^RaftServer restarted))
        (run! (fn [[id ^RaftServer s]]
                (when-not (= id follower)
                  (try (.close s) (catch Throwable _))))
              servers)))))

(defn -main [& _args]
  (probe-line :ratis-server-on-classpath (classpath-ratis-version))
  (let [tmp (str (Files/createTempDirectory
                   "ratis-probe" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (phase-a-and-b! tmp)
    (phase-c! tmp)
    (probe-line :done true)
    (System/exit 0)))
