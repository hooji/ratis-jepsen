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
  startup line with a deadline), kill (kill -9 by pidfile), wipe
  (/var/lib/ratis-kv), logs.

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
  this node's id, the full fixed voter set, the contract storage dir."
  [node]
  ["--id" node
   "--peers" (peers-spec env/initial-voters)
   "--storage" env/storage-dir])

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
  [node]
  (c/exec :mkdir :-p env/storage-dir)
  (apply cu/start-daemon!
         {:logfile env/log-file
          :pidfile pid-file
          :chdir   env/install-dir
          :match-executable? false}
         env/bin-path
         (server-args node)))

(defn kill!*
  "kill -9 by pidfile, then removes the pidfile. No-op if no pidfile."
  []
  (cu/stop-daemon! pid-file))

(defn wipe!
  "Removes the raft storage dir and the log."
  []
  (c/exec :rm :-rf env/storage-dir env/log-file))

;; ---------------------------------------------------------------------------
;; Jepsen DB
;; ---------------------------------------------------------------------------

(defrecord RatisKvDB []
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
  ;; nemesis calls these.
  jdb/Kill
  (start! [_this _test node]
    (start!* node))

  (kill! [_this _test _node]
    (kill!*)))

(defn db
  "The ratis-kv DB."
  []
  (RatisKvDB.))
