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

(ns ratis-jepsen.db
  "Node lifecycle for ratis-kv to the DESIGN 2.6 deployment contract:
  install (tarball from control → /opt/ratis-kv), start (contract CLI via
  start-stop-daemon, stdout → /var/log/ratis-kv.log, pidfile, await the
  startup line with a deadline), kill (kill -9 by pidfile), pause/resume
  (SIGSTOP/SIGCONT by pidfile), wipe (/var/lib/ratis-kv), logs — plus the
  best-effort leader census the crash nemesis uses for targeting bias.

  Layout: pure functions (command/argument construction, tarball
  selection) sit on top; everything that talks to a node through
  jepsen.control sits below and stays thin, so the interesting logic
  unit-tests without a cluster. Verified against real containers in
  Job 04, not here.

  All remote operations rely on the contract's `root` ssh user — no sudo
  wrapping."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.db :as jdb]
            [ratis-jepsen.env-contract :as env])
  (:import (java.io File)))

(def tarball-dir
  "Where the SUT tarball is found on the control node (Job 03 brief; the
  repo is mounted at /ratis-jepsen in the env topology)."
  "/ratis-jepsen/sut/ratis-kv/target")

(def tarball-name-pattern
  "Matches SUT tarball file names, e.g. ratis-kv-0.1.0-SNAPSHOT.tar.gz."
  #"ratis-kv-.*\.tar\.gz")

(def remote-tarball-path
  "Scratch location the tarball is uploaded to on each db node."
  "/tmp/ratis-kv.tar.gz")

(def pid-file
  "Pidfile written by start-stop-daemon on start and consumed by kill.
  (Harness-internal — not part of the DESIGN 2.6 contract.)"
  "/run/ratis-kv.pid")

(def startup-timeout-ms
  "How long a node gets from process start to the contract startup line
  before the run fails (a wedged start must be loud, fast)."
  30000)

(def startup-poll-ms
  "Poll interval while awaiting the startup line."
  500)

;; ---------------------------------------------------------------------------
;; Pure functions
;; ---------------------------------------------------------------------------

(defn peers-spec
  "The --peers value over the given nodes at the contract raft port, in
  node order: \"n1=n1:6000,n2=n2:6000,...\". Identical on every node."
  [nodes]
  (str/join "," (map #(str % "=" % ":" env/raft-port) nodes)))

(defn server-args
  "argv for bin/ratis-kv on `node` (the process contract, DESIGN 1.2):
  this node's id, the full fixed voter set, the contract storage dir —
  plus the seeded-bug flag when the run asks for one (test-of-the-test
  runs only; DESIGN 1.6)."
  [node seed-bug]
  (cond-> ["--id" node
           "--peers" (peers-spec env/initial-voters)
           "--storage" env/storage-dir]
    seed-bug (conj "--seed-bug" seed-bug)))

(defn join-server-args
  "argv for a node in --join mode (Job 08, membership pool): the peers
  list is the full 7-node address book (only the self entry is consumed,
  for the bind port), --join makes the server form no group — on empty
  storage it awaits GroupManagementApi.add; on existing storage the
  ratis-3.2.2 startup scan recovers the stored group, which makes this
  the correct restart mode for any dynamically-joined node too."
  [node seed-bug]
  (cond-> ["--id" node
           "--peers" (peers-spec env/all-nodes)
           "--storage" env/storage-dir
           "--join"]
    seed-bug (conj "--seed-bug" seed-bug)))

(defn dynamic-node?
  "Does `node` start in --join mode for this test? True iff the test map
  carries Job 08's membership state (an atom under :membership-state)
  and the node is in its :dynamic set — the initial pool (n6/n7) plus
  every node the membership nemesis has since moved through the pool.
  Non-membership runs carry no state and every node starts the M0 way."
  [test node]
  (boolean (some-> (:membership-state test) deref :dynamic (contains? node))))

(defn select-tarball
  "Picks the SUT tarball from a seq of file names. Exactly one match is
  the expected case; several (stale versions lying around) picks the
  lexicographically last and warns via the returned map; none throws.
  Returns {:name chosen :warning <string-or-nil>}."
  [names]
  (let [matches (sort (filter #(re-matches tarball-name-pattern %) names))]
    (case (count matches)
      0 (throw (ex-info (str "no SUT tarball matching " tarball-name-pattern
                             " — build it first: sut/ratis-kv/mvnw -f "
                             "sut/ratis-kv/pom.xml -q install")
                        {:candidates (vec names)}))
      1 {:name (first matches) :warning nil}
      {:name (last matches)
       :warning (str "multiple SUT tarballs " (vec matches)
                     "; using " (last matches))})))

;; ---------------------------------------------------------------------------
;; Control-side and node-side effects
;; ---------------------------------------------------------------------------

(defn find-tarball!
  "Resolves the tarball on the control node's filesystem."
  ^File []
  (let [dir   (File. ^String tarball-dir)
        names (or (seq (map #(.getName ^File %) (.listFiles dir))) [])
        {:keys [name warning]} (select-tarball names)]
    (when warning (log/warn warning))
    (File. dir ^String name)))

(defn install!
  "Uploads the SUT tarball from control and unpacks it to the contract
  install dir (tarball contents are bin/ and lib/ at top level)."
  [tarball]
  (c/exec :rm :-rf env/install-dir)
  (c/exec :mkdir :-p env/install-dir)
  (c/upload (.getPath ^File tarball) remote-tarball-path)
  (c/exec :tar :-xzf remote-tarball-path :-C env/install-dir)
  (c/exec :rm :-f remote-tarball-path))

(defn- log-content
  "Current contents of the node's log, or \"\" before the file exists."
  []
  (try (c/exec :cat env/log-file)
       (catch Exception _ "")))

(defn await-startup!
  "Blocks until the contract startup line (env-contract) appears in the
  node's log, throwing after `startup-timeout-ms` — a wedged start must
  fail the run, fast."
  [node]
  (let [deadline (+ (System/nanoTime) (* startup-timeout-ms 1000000))]
    (loop []
      (cond
        (re-find env/startup-line-pattern (log-content))
        (do (log/info node "ratis-kv started") true)

        (< (System/nanoTime) deadline)
        (do (Thread/sleep ^long startup-poll-ms) (recur))

        :else
        (throw (ex-info (str "ratis-kv on " node " did not emit the startup "
                             "line within " startup-timeout-ms " ms")
                        {:node node
                         :log-file env/log-file
                         :pattern (str env/startup-line-pattern)}))))))

(defn start!*
  "Starts the server on the current node via start-stop-daemon: contract
  CLI, stdout+stderr appended to the contract log, pidfile. Returns
  :started or :already-running (pidfile-based check only — the launcher
  script execs java, so executable matching would misfire)."
  ([node seed-bug]
   (start!* node seed-bug false))
  ([node seed-bug join?]
   (c/exec :mkdir :-p env/storage-dir)
   (apply cu/start-daemon!
          {:logfile env/log-file
           :pidfile pid-file
           :chdir   env/install-dir
           :match-executable? false}
          env/bin-path
          (if join?
            (join-server-args node seed-bug)
            (server-args node seed-bug)))))

(defn kill!*
  "kill -9 by pidfile, then removes the pidfile. No-op if no pidfile."
  []
  (cu/stop-daemon! pid-file))

(defn- signal!*
  "Sends `sig` (e.g. \"STOP\") to the pidfile's process on the current
  node. Returns `ok` on delivery, :no-pidfile when no pidfile exists
  (never started, or removed by a kill), :stale-pidfile when the pidfile
  names a process that is gone. Tolerant on purpose: the pause nemesis
  must degrade to a reported no-op, not crash, when it races a restart."
  [sig ok]
  (if-let [pid (try (c/exec :cat pid-file) (catch Exception _ nil))]
    (try (c/exec :kill (str "-" sig) pid)
         ok
         (catch Exception _
           (log/warn "kill -" sig pid "failed — stale pidfile?")
           :stale-pidfile))
    :no-pidfile))

(defn pause!*
  "SIGSTOP the server process by pidfile (the launcher execs java, so the
  pid is the JVM itself). The process keeps its sockets; peers see it as
  present-but-silent until resume."
  []
  (signal!* "STOP" :paused))

(defn resume!*
  "SIGCONT the server process by pidfile. Harmless if it was not stopped."
  []
  (signal!* "CONT" :resumed))

(defn wipe!
  "Removes the raft storage dir and the log."
  []
  (c/exec :rm :-rf env/storage-dir env/log-file))

(defn wipe-storage!
  "Removes the raft storage dir ONLY, preserving the log (Job 08: the
  membership nemesis returns a removed/replaced node to the clean pool
  posture mid-run — the storage must go so the node can be re-added as a
  fresh joiner, but the log is run evidence and must survive to the
  store snarf)."
  []
  (c/exec :rm :-rf env/storage-dir))

;; ---------------------------------------------------------------------------
;; Leader census (best-effort, for nemesis targeting bias only)
;; ---------------------------------------------------------------------------

(defn leader-transition?
  "True iff a Ratis role-transition log line records a transition TO
  leader (e.g. \"n1@group-...: changes role from CANDIDATE to LEADER at
  term 2 ...\"). Same convention as env/validate.sh check (b)."
  [line]
  (boolean (and line (re-find #"changes role from \S+ to LEADER" line))))

(defn last-role-transition!
  "The last role-transition line in the current node's log, or nil when
  the log has none (or does not exist)."
  []
  (let [out (try (c/exec :bash :-c (str "grep 'changes role from' "
                                        env/log-file
                                        " 2>/dev/null | tail -n 1"))
                 (catch Exception _ ""))]
    (when-not (str/blank? out) out)))

(defn current-leaders!
  "Best-effort census of the nodes that currently believe they lead: a
  node counts iff the LAST role transition in its log is to LEADER
  (current leadership, not election history — validate.sh check (b)).
  Wrong answers are possible mid-election, or on a node killed before it
  could log a demotion; callers use this to BIAS fault targeting, never
  for correctness."
  [test]
  (->> (c/on-nodes test (fn [_test _node]
                          (leader-transition? (last-role-transition!))))
       (keep (fn [[node leader?]] (when leader? node)))
       vec))

;; ---------------------------------------------------------------------------
;; Conf census (Job 08). Every replica logs each configuration it adopts:
;;
;;   <id>@group-…: set configuration conf: {index: 5, cur=peers:[n1|n1:6000,
;;   n2|n2:6000]|listeners:[n7|n7:6000], old=null}
;;
;; (ServerState.setRaftConf at ratis-3.2.2; phrasing observed live in the
;; SUT's JoinModeTest and pinned there and in the evidence checker.) The
;; highest index across nodes is the best available estimate of the
;; current conf — the API alternative, GroupInfoReply's conf field, is
;; dropped by the 3.2.2 wire serializer and always arrives empty. The
;; census backs the membership nemesis's COMPARE_AND_SET arguments; a
;; stale answer surfaces as SetConfigurationException and is retried,
;; never trusted for correctness.
;; ---------------------------------------------------------------------------

(def conf-line-pattern
  "Matches one 'set configuration' log line, capturing the conf index,
  the cur= servers list body, the cur= listeners list body and the old=
  tail (null for a stable conf, peers:… for a transitional one)."
  #"set configuration conf: \{index: (\d+), cur=peers:\[([^\]]*)\]\|listeners:\[([^\]]*)\], old=(null|peers:.*)\}")

(defn- parse-peer-ids
  "Node ids from a conf-line peer-list body: \"n1|n1:6000, n2|n2:6000\"
  -> [\"n1\" \"n2\"]. Empty body -> []."
  [body]
  (if (str/blank? body)
    []
    (mapv #(first (str/split % #"\|"))
          (str/split (str/trim body) #",\s*"))))

(defn parse-conf-line
  "Parses a 'set configuration' log line into
  {:index long, :servers [ids], :listeners [ids], :stable? bool}, or nil
  when the line does not match (pure; unit-tested)."
  [line]
  (when line
    (when-let [[_ index servers listeners old] (re-find conf-line-pattern line)]
      {:index     (Long/parseLong index)
       :servers   (parse-peer-ids servers)
       :listeners (parse-peer-ids listeners)
       :stable?   (= old "null")})))

(defn last-conf-line!
  "The last 'set configuration' line in the current node's log, or nil."
  []
  (let [out (try (c/exec :bash :-c (str "grep 'set configuration conf' "
                                        env/log-file
                                        " 2>/dev/null | tail -n 1"))
                 (catch Exception _ ""))]
    (when-not (str/blank? out) out)))

(defn conf-line-count!
  "How many 'set configuration' lines the current node's log holds — the
  listener probe's replication census (conf entries are ordinary log
  entries, so their arrival proves log replication reached the node)."
  []
  (let [out (try (c/exec :bash :-c (str "grep -c 'set configuration conf' "
                                        env/log-file " 2>/dev/null || true"))
                 (catch Exception _ "0"))]
    (try (Long/parseLong (str/trim out))
         (catch Exception _ 0))))

(defn conf-census!
  "Best-effort census of the group's current configuration: every node's
  last adopted conf, highest index wins (a removed or lagging node's
  view is older, never newer). Returns {:index n, :servers [...],
  :listeners [...], :stable? b} or nil when no node has adopted any conf
  yet (cluster still electing its first leader)."
  [test]
  (->> (c/on-nodes test (fn [_test _node]
                          (parse-conf-line (last-conf-line!))))
       vals
       (keep identity)
       (sort-by :index)
       last))

;; ---------------------------------------------------------------------------
;; Jepsen DB
;; ---------------------------------------------------------------------------

(defrecord RatisKvDB [seed-bug]
  jdb/DB
  (setup! [this test node]
    (install! (find-tarball!))
    (jdb/start! this test node)
    (await-startup! node))

  (teardown! [this test node]
    (jdb/kill! this test node)
    (wipe!))

  jdb/LogFiles
  (log-files [_this _test _node]
    [env/log-file])

  ;; Kill/restart primitives from day one (DESIGN 2.2) — M1's crash
  ;; nemesis calls these. Restart goes through the same start!* as first
  ;; boot: the SUT opens its storage with StartupOption.RECOVER either way.
  ;; Membership runs (Job 08) start :dynamic nodes — the pool plus every
  ;; node that has been through it — in --join mode: fresh storage awaits
  ;; bootstrap, existing storage recovers, so the same call is right for
  ;; first boots, crash restarts and pool returns alike.
  jdb/Kill
  (start! [_this test node]
    (start!* node seed-bug (dynamic-node? test node)))

  (kill! [_this _test _node]
    (kill!*))

  ;; SIGSTOP/SIGCONT primitives — M1's pause nemesis calls these.
  jdb/Pause
  (pause! [_this _test _node]
    (pause!*))

  (resume! [_this _test _node]
    (resume!*)))

(defn db
  "The ratis-kv DB; pass a seed-bug mode name (e.g. \"stale-reads\") to
  start every node with that deliberately seeded SUT bug."
  ([]
   (db nil))
  ([seed-bug]
   (RatisKvDB. seed-bug)))
