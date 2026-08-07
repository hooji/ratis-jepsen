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

;; ---------------------------------------------------------------------------
;; lazyfs (M4, Job 11). All harness-internal — not part of the DESIGN 2.6
;; contract, which pins only the storage dir the SUT is told to use. In a
;; durability run that same path becomes a lazyfs MOUNTPOINT whose backing
;; store is lazyfs-backing-dir, so the SUT's --storage argument and every
;; other path in the harness stay exactly as they are.
;; ---------------------------------------------------------------------------

(def lazyfs-bin
  "The lazyfs binary baked into the env image (env/Dockerfile, pinned)."
  "/opt/lazyfs/bin/lazyfs")

(def lazyfs-backing-dir
  "The real-filesystem directory a durability run's mount is backed by;
  everything lazyfs actually persists lands here."
  "/var/lib/ratis-kv.lazyfs-root")

(def lazyfs-config-file "/run/lazyfs.toml")
(def lazyfs-pid-file "/run/lazyfs.pid")

(def lazyfs-fifo
  "lazyfs's control pipe: the durability nemeses echo commands
  (lazyfs::clear-cache) into it."
  "/run/lazyfs.fifo")

(def lazyfs-log-file
  "lazyfs's stdout, collected per run. Doubles as THE MOUNT EVIDENCE
  source (the mount line is appended here at mount time) and as the
  fault record (lazyfs acknowledges each cache command here)."
  "/var/log/lazyfs.log")

(def lazyfs-cache-size
  "Per-node cache size. The default config ships 1 GiB, which the spike
  measured at ~8 s of pre-allocation per mount — ×5 nodes of startup
  budget and 5 GiB of RAM. Our per-node storage is a 4 MiB preallocated
  log segment plus small snapshot/meta files, so 64 MiB is ample for
  everything a run can leave un-synced while making the mount ~instant
  (measured cost in the Job 11 report)."
  "64mb")

(def lazyfs-mount-timeout-ms
  "How long a node's mount gets to appear in /proc/mounts before the run
  fails loudly — a durability run that silently ran on the plain
  filesystem is a broken test."
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
  plus the test levers when the run asks for them: the seeded-bug flag
  (DESIGN 1.6) and the Job 09/Q14 retry-cache expiry override (absent ⇒
  the Ratis default 60 s stays untouched)."
  [node seed-bug retry-cache-expiry-ms]
  (cond-> ["--id" node
           "--peers" (peers-spec env/initial-voters)
           "--storage" env/storage-dir]
    retry-cache-expiry-ms (conj "--retry-cache-expiry-ms"
                                (str retry-cache-expiry-ms))
    seed-bug (conj "--seed-bug" seed-bug)))

(defn join-server-args
  "argv for a node in --join mode (Job 08, membership pool): the peers
  list is the full 7-node address book (only the self entry is consumed,
  for the bind port), --join makes the server form no group — on empty
  storage it awaits GroupManagementApi.add; on existing storage the
  ratis-3.2.2 startup scan recovers the stored group, which makes this
  the correct restart mode for any dynamically-joined node too."
  [node seed-bug retry-cache-expiry-ms]
  (cond-> ["--id" node
           "--peers" (peers-spec env/all-nodes)
           "--storage" env/storage-dir
           "--join"]
    retry-cache-expiry-ms (conj "--retry-cache-expiry-ms"
                                (str retry-cache-expiry-ms))
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
   (start!* node seed-bug false nil))
  ([node seed-bug join?]
   (start!* node seed-bug join? nil))
  ([node seed-bug join? retry-cache-expiry-ms]
   (c/exec :mkdir :-p env/storage-dir)
   (apply cu/start-daemon!
          {:logfile env/log-file
           :pidfile pid-file
           :chdir   env/install-dir
           :match-executable? false}
          env/bin-path
          (if join?
            (join-server-args node seed-bug retry-cache-expiry-ms)
            (server-args node seed-bug retry-cache-expiry-ms)))))

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

;; ---------------------------------------------------------------------------
;; lazyfs mount lifecycle (M4). The mount must exist before the SUT starts
;; and outlive every kill/restart cycle (lazyfs is its own process), so it
;; is created in setup! and torn down in teardown! — never by the nemeses,
;; which only send it fault commands over the fifo.
;; ---------------------------------------------------------------------------

(defn lazyfs-config
  "The lazyfs toml for one node. `injection` is an optional extra block
  (the torn-write nemesis configures its fault statically — lazyfs takes
  torn faults from the config, not the fifo). Pure, so the shape is
  unit-testable."
  ([] (lazyfs-config nil))
  ([injection]
   (str "[faults]\n"
        "fifo_path=\"" lazyfs-fifo "\"\n"
        "[cache]\n"
        "apply_eviction=false\n"
        "[cache.simple]\n"
        "custom_size=\"" lazyfs-cache-size "\"\n"
        "blocks_per_page=1\n"
        "[filesystem]\n"
        "log_all_operations=false\n"
        "logfile=\"\"\n"
        (or injection ""))))

(def lazyfs-backing-current-dir
  "The SUT's `current/` directory as lazyfs sees it (backing path)."
  (str lazyfs-backing-dir "/" env/group-uuid "/current"))

(defn resolve-torn-target!
  "The file the torn-write fault should tear: whichever log segment the
  SUT is CURRENTLY appending to, resolved on the node at arm time.

  Resolved rather than hardcoded because Ratis renames its open segment
  on every restart — `log_inprogress_0` becomes `log_0-10` and a fresh
  `log_inprogress_11` opens (observed in Job 10's rehearsal and again
  here). A fixed `log_inprogress_0` therefore stops matching after the
  first restart, which is exactly when this fault arms, so the
  injection registered but never fired — two preserved runs' worth of
  `:no-durability-fault-evidence` before the evidence law made the
  cause obvious. Returns nil when no segment exists yet (first boot),
  in which case nothing is armed."
  []
  (let [out (try (c/exec :bash :-c
                         (str "ls -1t " lazyfs-backing-current-dir
                              "/log_inprogress_* 2>/dev/null | head -n 1"))
                 (catch Exception _ ""))]
    (when-not (str/blank? out) (str/trim out))))

(def torn-write-occurrence
  "Which write to the target file gets torn, counted from when the
  injection is registered. ONE: the very next write after the mount is
  armed.

  This is deliberately the first write, and the arming point is what
  makes the fault land on real data: the injection is NOT armed at
  setup (the node's first cycle runs clean and accumulates committed
  entries) but on every REMOUNT, so from the second cycle on, the first
  write after each restart tears a log segment that already holds
  committed entries. A larger occurrence was tried first and never
  fired — the SUT does not issue that many writes to one segment inside
  a cycle — and the evidence law caught it (:no-durability-fault-evidence,
  preserved run in the ledger), which is exactly what the law is for."
  1)

(defn torn-write-injection
  "A lazyfs `torn-op` block: split the `occurrence`-th write to `file`
  into `parts` pieces, persist only the parts listed in `persist`, and
  (lazyfs's own behavior) crash the filesystem afterwards — a partial
  write surviving a power cut. Persisting parts 1 and 3 of 3 leaves a
  hole in the middle of the record, which is the shape a real torn
  sector takes."
  ([file] (torn-write-injection file torn-write-occurrence 3 [1 3]))
  ([file occurrence parts persist]
   (str "[[injection]]\n"
        "type=\"torn-op\"\n"
        "file=\"" file "\"\n"
        "occurrence=" occurrence "\n"
        "parts=" parts "\n"
        "persist=[" (str/join "," persist) "]\n")))

(defn mounted?
  "Is the contract storage dir currently a lazyfs mount on this node?
  Reads /proc/mounts — the ground truth, not our own belief."
  []
  (let [out (try (c/exec :bash :-c
                         (str "grep -c ' " env/storage-dir " fuse.lazyfs ' "
                              "/proc/mounts || true"))
                 (catch Exception _ "0"))]
    (pos? (try (Long/parseLong (str/trim out)) (catch Exception _ 0)))))

(defn await-mount!
  "Blocks until the lazyfs mount appears, throwing after
  lazyfs-mount-timeout-ms. A durability run that silently ran on the
  plain filesystem is a broken test, so a mount that never appears must
  fail the run loudly and immediately."
  [node]
  (let [deadline (+ (System/nanoTime) (* lazyfs-mount-timeout-ms 1000000))]
    (loop []
      (cond
        (mounted?) (do (log/info node "lazyfs mounted at" env/storage-dir) true)
        (< (System/nanoTime) deadline)
        (do (Thread/sleep ^long startup-poll-ms) (recur))
        :else
        (throw (ex-info (str "lazyfs did not mount " env/storage-dir " on "
                             node " within " lazyfs-mount-timeout-ms " ms — "
                             "refusing to run a durability test on the plain "
                             "filesystem")
                        {:node node :mountpoint env/storage-dir}))))))

(defn mount-lazyfs!
  "Mounts lazyfs over the contract storage dir on the current node:
  fresh backing dir + mountpoint, per-node config, then the daemon under
  start-stop-daemon (foreground mode so the pidfile is the real process
  and stdout lands in the collected log). Appends the /proc/mounts line
  to that log as the run's MOUNT EVIDENCE."
  [node injection]
  (c/exec :rm :-rf lazyfs-backing-dir)
  (c/exec :mkdir :-p lazyfs-backing-dir env/storage-dir)
  (c/exec :rm :-f lazyfs-fifo)
  ;; Truncate the log: it is this run's EVIDENCE, and a stale mount line
  ;; from a previous run must never satisfy the evidence law.
  (c/exec :bash :-c (str ": > " lazyfs-log-file))
  (cu/write-file! (lazyfs-config injection) lazyfs-config-file)
  (cu/start-daemon!
    {:logfile lazyfs-log-file
     :pidfile lazyfs-pid-file
     :chdir   "/"
     :match-executable? false}
    lazyfs-bin
    env/storage-dir
    "--config-path" lazyfs-config-file
    "-o" "allow_other"
    "-o" "modules=subdir"
    "-o" (str "subdir=" lazyfs-backing-dir)
    "-s"
    "-f")
  (await-mount! node)
  ;; The evidence line, from the kernel's own view, into the log jepsen
  ;; snarfs before teardown (checkers run after teardown — Job 07's
  ;; lesson — so evidence must live in a collected file).
  (c/exec :bash :-c (str "grep ' " env/storage-dir " fuse.lazyfs ' /proc/mounts"
                         " >> " lazyfs-log-file))
  :mounted)

(defn remount-lazyfs!
  "Re-mounts lazyfs over the storage dir PRESERVING the backing store —
  the torn-write heal. lazyfs crashes itself when a torn fault fires, so
  the mount must be rebuilt before the SUT can restart; wiping the
  backing store instead would erase committed data that exists nowhere
  else, which is an out-of-model fault (BACKLOG item 4), not a power
  cut. Any `injection` is re-armed, so every cycle tears a write rather
  than only the first. The log is NOT truncated here: this run's earlier
  evidence must survive its own remounts."
  ([node] (remount-lazyfs! node nil))
  ([node torn?]
   (try (c/exec :fusermount3 :-u env/storage-dir)
        (catch Exception _ nil))
   (try (cu/stop-daemon! lazyfs-pid-file)
        (catch Exception _ nil))
   (c/exec :mkdir :-p lazyfs-backing-dir env/storage-dir)
   (c/exec :rm :-f lazyfs-fifo)
   ;; Resolve the live segment AFTER the unmount (the backing store is
   ;; directly readable then) and arm the tear on it.
   (let [injection (when torn?
                     (when-let [target (resolve-torn-target!)]
                       (log/info node "arming torn-write on" target)
                       (torn-write-injection target)))]
     (cu/write-file! (lazyfs-config injection) lazyfs-config-file))
   (cu/start-daemon!
     {:logfile lazyfs-log-file
      :pidfile lazyfs-pid-file
      :chdir   "/"
      :match-executable? false}
     lazyfs-bin
     env/storage-dir
     "--config-path" lazyfs-config-file
     "-o" "allow_other"
     "-o" "modules=subdir"
     "-o" (str "subdir=" lazyfs-backing-dir)
     "-s"
     "-f")
   (await-mount! node)
   :remounted))

(defn unmount-lazyfs!
  "Unmounts lazyfs and stops its daemon. Tolerant: teardown must finish
  even if the mount is already gone (a torn-write fault crashes lazyfs
  by design)."
  []
  (try (c/exec :fusermount3 :-u env/storage-dir)
       (catch Exception e
         (log/warn "fusermount3 -u failed (already unmounted?):"
                   (.getMessage e))))
  (try (cu/stop-daemon! lazyfs-pid-file)
       (catch Exception e
         (log/warn "stopping lazyfs daemon failed:" (.getMessage e))))
  (c/exec :rm :-rf lazyfs-backing-dir)
  :unmounted)

(defn drop-unsynced!
  "Sends lazyfs's clear-cache command: everything written through the
  mount but never fsynced is discarded — the power-loss fault. The
  daemon acknowledges into its log, which is the run's fault evidence."
  []
  (c/exec :bash :-c (str "echo 'lazyfs::clear-cache' > " lazyfs-fifo))
  :dropped)

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

(defrecord RatisKvDB [seed-bug retry-cache-expiry-ms durability]
  jdb/DB
  (setup! [this test node]
    (install! (find-tarball!))
    ;; The mount goes up BEFORE the SUT so the server's very first write
    ;; already lands on lazyfs; a failed mount throws here and fails the
    ;; run before a single op is issued.
    (when durability
      ;; No injection at setup: torn-write arms on REMOUNT, so the first
      ;; cycle runs clean and the tear lands on a log that already holds
      ;; committed entries (and on a segment name that exists).
      (mount-lazyfs! node nil))
    (jdb/start! this test node)
    (await-startup! node))

  (teardown! [this test node]
    (jdb/kill! this test node)
    ;; Unmount BEFORE the wipe: rm -rf through a live FUSE mountpoint
    ;; deletes the data but cannot remove the mountpoint, leaving a
    ;; stale mount behind for the next run.
    (when durability
      (unmount-lazyfs!))
    (wipe!))

  jdb/LogFiles
  (log-files [_this _test _node]
    (cond-> [env/log-file]
      durability (conj lazyfs-log-file)))

  ;; Kill/restart primitives from day one (DESIGN 2.2) — M1's crash
  ;; nemesis calls these. Restart goes through the same start!* as first
  ;; boot: the SUT opens its storage with StartupOption.RECOVER either way.
  ;; Membership runs (Job 08) start :dynamic nodes — the pool plus every
  ;; node that has been through it — in --join mode: fresh storage awaits
  ;; bootstrap, existing storage recovers, so the same call is right for
  ;; first boots, crash restarts and pool returns alike.
  jdb/Kill
  (start! [_this test node]
    (start!* node seed-bug (dynamic-node? test node) retry-cache-expiry-ms))

  (kill! [_this _test _node]
    (kill!*))

  ;; SIGSTOP/SIGCONT primitives — M1's pause nemesis calls these.
  jdb/Pause
  (pause! [_this _test _node]
    (pause!*))

  (resume! [_this _test _node]
    (resume!*)))

(defn db
  "The ratis-kv DB. Options:
    :seed-bug              deliberately seeded SUT bug (e.g. \"stale-reads\")
    :retry-cache-expiry-ms Job 09/Q14 lever; nil leaves the Ratis default
    :durability            nil (default — plain filesystem, every existing
                           scenario byte-for-byte unchanged) or a map that
                           turns the storage dir into a lazyfs mount,
                           optionally carrying {:injection <toml block>}
                           for the torn-write fault"
  ([] (db {}))
  ([{:keys [seed-bug retry-cache-expiry-ms durability]}]
   (RatisKvDB. seed-bug retry-cache-expiry-ms durability)))
