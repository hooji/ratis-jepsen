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

  Durability mode (Job 11, M4): with :durability set, each node's storage
  dir is a lazyfs FUSE mount over env/backing-dir, mounted at setup!
  before the SUT first starts and outliving every SUT kill/restart cycle
  (lazyfs is its own daemon). THE EVIDENCE LAW: a durability run that is
  not actually on lazyfs is a broken test, so the mount is PROVEN from
  the node — mount table entry, fault fifo, and an fsync'd canary
  observed in the backing dir — and any shortfall throws the distinct
  ::durability-mount-unproven error, failing the run loudly rather than
  silently testing the plain filesystem. See the lazyfs section below.

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

(defn wipe-storage!
  "Removes the raft storage dir ONLY, preserving the log (Job 08: the
  membership nemesis returns a removed/replaced node to the clean pool
  posture mid-run — the storage must go so the node can be re-added as a
  fresh joiner, but the log is run evidence and must survive to the
  store snarf).

  With `durability?` the storage dir is a live lazyfs mountpoint, so the
  wipe empties it THROUGH the mount (contents only — removing the
  mountpoint dir itself would fail with EBUSY and killing the mount
  would put the fresh joiner on the plain filesystem, breaking the
  evidence law mid-run)."
  ([] (wipe-storage! false))
  ([durability?]
   (if durability?
     (c/exec :bash :-c (str "find " env/storage-dir
                            " -mindepth 1 -delete 2>/dev/null || true"))
     (c/exec :rm :-rf env/storage-dir))))

;; ---------------------------------------------------------------------------
;; lazyfs mount lifecycle (Job 11, M4). The storage stack on durability
;; runs, per the Job 10 spike's recommended shape:
;;
;;   /var/lib/ratis-kv        the contract storage dir (env/storage-dir),
;;                            now a lazyfs FUSE mountpoint — nothing else
;;                            in the harness or SUT moves
;;   /var/lib/ratis-kv.root   the backing dir (env/backing-dir): what
;;                            lazyfs has persisted here is what survives
;;                            a simulated power loss; what sits only in
;;                            its page cache is droppable on command
;;   /run/lazyfs-faults.fifo  lazyfs's fault-command pipe (created by
;;                            lazyfs); the durability nemeses write
;;                            lazyfs::* commands into it over ssh
;;   /var/log/lazyfs.log      lazyfs stdout (fault acknowledgements) —
;;                            collected into store/ per run; the
;;                            durability evidence checker reads it
;;
;; lazyfs runs as its own daemon (start-stop-daemon, pidfile) and
;; deliberately OUTLIVES SUT kill/restart cycles; only teardown/wipe (and
;; the torn-write nemesis's remount, after lazyfs kills itself) touch it.
;; ---------------------------------------------------------------------------

(def lazyfs-pid-file
  "Pidfile for the lazyfs daemon (harness-internal)."
  "/run/lazyfs.pid")

(def lazyfs-fifo
  "The fault-command named pipe, from the per-node toml (lazyfs creates
  it). Outside the mount and the backing dir on purpose."
  "/run/lazyfs-faults.fifo")

(def lazyfs-config-path
  "Where the per-node lazyfs toml is written at mount time."
  "/run/lazyfs-ratis.toml")

(def lazyfs-cache-size
  "lazyfs page-cache size per node. Deliberate (brief deliverable 2):
  the default 1 GiB costs ~4–8 s pre-allocation and 1 GiB RSS per mount —
  ×5 nodes would exhaust a hosted runner. Our per-node storage is a few
  4 MiB-preallocated log segments plus KB-scale snapshots (tens of MiB
  worst case), and the cache must comfortably hold ALL pages touched:
  with apply_eviction=false a FULL cache makes lazyfs write through to
  the backing store, which would silently defeat the un-synced-drop
  faults. 128 MiB is ~4× the worst case observed in shakedown runs and
  pre-allocates in under a second."
  "128mb")

(defn lazyfs-config
  "The per-node lazyfs toml (pure; unit-tested). Faults are injected at
  runtime through the fifo, so no static [[injection]] blocks; eviction
  off so nothing is silently written through; logging left on stdout
  (captured to lazyfs-log-file by the daemon wrapper)."
  []
  (str "[faults]\n"
       "fifo_path=\"" lazyfs-fifo "\"\n"
       "[cache]\n"
       "apply_eviction=false\n"
       "[cache.simple]\n"
       "custom_size=\"" lazyfs-cache-size "\"\n"
       "blocks_per_page=1\n"
       "[filesystem]\n"
       "log_all_operations=false\n"
       "logfile=\"\"\n"))

(def lazyfs-mount-timeout-ms
  "Deadline for the lazyfs mount to appear in /proc/mounts after the
  daemon starts (covers the cache pre-allocation, <1 s at 128 MiB)."
  60000)

(def mount-unproven-error
  "The distinct marker for the durability evidence law: any failure to
  prove the mount carries this string (and :type
  :durability-mount-unproven) so a broken durability run is
  unmistakable in logs and CI output."
  "DURABILITY MOUNT UNPROVEN")

(defn- mount-unproven!
  "Throws the distinct durability-evidence error."
  [node step detail]
  (throw (ex-info (str mount-unproven-error " on " node " (" step "): "
                       detail
                       " — a durability run that is not actually on lazyfs"
                       " is a broken test; refusing to continue")
                  {:type :durability-mount-unproven
                   :node node
                   :step step})))

(defn lazyfs-mounted?
  "Is the contract storage dir currently a fuse.lazyfs mount on this
  node? Read from /proc/mounts — the node's own mount table, not
  harness belief."
  []
  (-> (try (c/exec :bash :-c (str "grep -F ' " env/storage-dir
                                  " fuse.lazyfs ' /proc/mounts || true"))
           (catch Exception _ ""))
      str/blank?
      not))

(defn- lazyfs-log-tail
  "Last lines of the node's lazyfs log, for error context."
  []
  (try (c/exec :bash :-c (str "tail -n 5 " env/lazyfs-log-file
                              " 2>/dev/null || true"))
       (catch Exception _ "")))

(defn start-lazyfs!
  "Starts the lazyfs daemon for storage-dir over backing-dir and waits
  until the mount appears. Assumes mount-lazyfs!'s preconditions (binary
  present, dirs exist, toml written); used by mount-lazyfs! and by the
  torn-write remount."
  [node]
  (cu/start-daemon!
    {:logfile env/lazyfs-log-file
     :pidfile lazyfs-pid-file
     :chdir   "/"
     :match-executable? false}
    env/lazyfs-bin
    env/storage-dir
    "--config-path" lazyfs-config-path
    "-o" "allow_other"
    "-o" "modules=subdir"
    "-o" (str "subdir=" env/backing-dir)
    "-f")
  (let [deadline (+ (System/nanoTime) (* lazyfs-mount-timeout-ms 1000000))]
    (loop []
      (cond
        (lazyfs-mounted?) true

        (< (System/nanoTime) deadline)
        (do (Thread/sleep 500) (recur))

        :else
        (mount-unproven! node :mount-await
                         (str "no fuse.lazyfs mount on " env/storage-dir
                              " within " lazyfs-mount-timeout-ms
                              " ms; lazyfs log tail: " (lazyfs-log-tail)))))))

(defn prove-mount!
  "The evidence law, executed on the node: (1) storage-dir is a
  fuse.lazyfs mount per /proc/mounts, (2) the fault fifo exists, (3) a
  canary written through the mount and fsync'd (the pure-fsync append
  trick — no rewrite) is observed byte-identical in the BACKING dir,
  i.e. writes really flow through lazyfs into the backing store. Throws
  the distinct error on any shortfall."
  [node]
  (when-not (lazyfs-mounted?)
    (mount-unproven! node :mount-table
                     (str "no fuse.lazyfs entry for " env/storage-dir
                          " in /proc/mounts")))
  (when (str/blank? (try (c/exec :bash :-c (str "test -p " lazyfs-fifo
                                                " && echo yes || true"))
                         (catch Exception _ "")))
    (mount-unproven! node :fault-fifo
                     (str lazyfs-fifo " is not a named pipe")))
  (let [canary  (str "canary-" node "-" (System/nanoTime))
        mnt     (str env/storage-dir "/.mount-canary")
        backing (str env/backing-dir "/.mount-canary")
        seen    (try
                  (c/exec :bash :-c
                          (str "printf %s " canary " > " mnt
                               " && dd if=/dev/null of=" mnt
                               " oflag=append conv=fsync,notrunc"
                               " status=none"
                               " && cat " backing " 2>/dev/null"
                               "; rm -f " mnt))
                  (catch Exception e
                    (str "canary sequence failed: " (.getMessage e))))]
    (when-not (= canary seen)
      (mount-unproven! node :fsync-canary
                       (str "wrote " (pr-str canary) " through the mount, "
                            "backing dir shows " (pr-str seen)))))
  (log/info node "durability mount proven (lazyfs on" env/storage-dir
            "over" env/backing-dir ")")
  true)

(defn mount-lazyfs!
  "Mounts storage-dir as lazyfs over backing-dir on the current node and
  PROVES it (the evidence law). Called from setup! before the SUT first
  starts. Fails the run with the distinct ::durability-mount-unproven
  error rather than ever running a durability test on the plain
  filesystem."
  [node]
  (when (str/blank? (try (c/exec :bash :-c (str "test -x " env/lazyfs-bin
                                                " && echo yes || true"))
                         (catch Exception _ "")))
    (mount-unproven! node :lazyfs-binary
                     (str env/lazyfs-bin " is missing or not executable"
                          " (non-amd64 image? see env/README.md)")))
  (c/exec :mkdir :-p env/backing-dir env/storage-dir)
  (cu/write-file! (lazyfs-config) lazyfs-config-path)
  (start-lazyfs! node)
  (prove-mount! node))

(defn unmount-lazyfs!
  "Tears the lazyfs mount down: detach the mountpoint (lazy -z, which
  also clears the ghost mount a self-killed lazyfs leaves behind), then
  kill any lazyfs daemon by pidfile. Tolerant of every absent state —
  teardown! must work on a node that never mounted."
  []
  (c/exec :bash :-c (str "fusermount3 -uz " env/storage-dir
                         " 2>/dev/null || true"))
  (cu/stop-daemon! lazyfs-pid-file))

(defn wipe-durability!
  "The durability-mode wipe: after unmount, removes the backing dir, the
  (now plain) mountpoint dir, both logs and the lazyfs runtime files."
  []
  (c/exec :rm :-rf env/backing-dir env/storage-dir env/log-file
          env/lazyfs-log-file lazyfs-config-path lazyfs-fifo))

;; ---------------------------------------------------------------------------
;; lazyfs fault commands (the durability nemeses' surface). All commands go
;; through the fault fifo; the echo is timeout-wrapped because a fifo write
;; blocks forever when no reader exists (lazyfs dead) — a failed send is a
;; RECORDED outcome for the nemesis, never a hang.
;; ---------------------------------------------------------------------------

(defn lazyfs-command!
  "Writes one lazyfs::* command line into the node's fault fifo. Returns
  :sent, or :send-failed when the write cannot complete within 5 s (no
  lazyfs reading — dead daemon or no fifo)."
  [command]
  (let [out (try (c/exec :bash :-c
                         (str "timeout 5 sh -c 'echo \"" command "\" > "
                              lazyfs-fifo "' && echo sent || echo failed"))
                 (catch Exception _ "failed"))]
    (if (= "sent" (str/trim out)) :sent :send-failed)))

(defn clear-cache!
  "The un-synced-data drop: everything lazyfs has not persisted to the
  backing dir is discarded — the simulated power loss on this node's
  storage. (The SUT process is killed FIRST by the nemesis: the truer
  power-loss order, Job 10 fault ordering B.)"
  []
  (lazyfs-command! "lazyfs::clear-cache"))

(defn current-open-segment!
  "The BACKING path of the node's current open raft log segment
  (log_inprogress_<start> with the highest start index) — the torn-write
  fault's target file. lazyfs keys torn faults on exact backing paths
  (README: 'the absolute path using the root directory'). Nil when no
  open segment exists yet."
  []
  (let [out (try (c/exec :bash :-c
                         (str "find " env/storage-dir
                              " -name 'log_inprogress_*' 2>/dev/null"
                              " | sort -t_ -k3 -n | tail -n 1"))
                 (catch Exception _ ""))]
    (when-not (str/blank? out)
      (str/replace-first (str/trim out) env/storage-dir env/backing-dir))))

(defn torn-write-command
  "The lazyfs torn-op fifo command (pure; the grammar is the lazyfs
  README's — parameters that hold multiple values lose their brackets):
  tear the `occurrence`-th write to `backing-file` into `parts` equal
  parts and persist only part 1 (the head survives, the tail dies with
  the cache)."
  [backing-file parts occurrence]
  (str "lazyfs::torn-op::file=" backing-file
       "::parts=" parts "::persist=1"
       "::occurrence=" occurrence))

(defn torn-write!
  "Arms lazyfs's torn-op fault on `backing-file`: the `occurrence`-th
  write to it after arming is split into `parts` equal parts of which
  only part 1 reaches the backing store; lazyfs then SIGKILLs itself, so
  the rest of that write — and everything else un-synced — dies with its
  cache. The tear lands at the tail of the durable log: exactly a
  power loss mid-write."
  [backing-file parts occurrence]
  (lazyfs-command! (torn-write-command backing-file parts occurrence)))

(defn torn-write-fired?
  "Did an armed torn-op actually fire on this node? lazyfs logs the
  persisted-part write(s) and its own SIGKILL when the fault triggers;
  either line in the lazyfs log is proof."
  []
  (-> (try (c/exec :bash :-c
                   (str "grep -E 'will persist [0-9]+ bytes|Killing LazyFS pid' "
                        env/lazyfs-log-file " 2>/dev/null | head -n 1"))
           (catch Exception _ ""))
      str/blank?
      not))

(defn remount-lazyfs!
  "The torn-write recovery path: clear the dead mount a self-killed
  lazyfs leaves behind, then mount fresh (empty cache) over the torn
  backing store, re-proving the mount — a victim silently returned to
  the plain filesystem would break the run's evidence."
  [node]
  (unmount-lazyfs!)
  (start-lazyfs! node)
  (prove-mount! node))

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

(defrecord RatisKvDB [seed-bug retry-cache-expiry-ms durability?]
  jdb/DB
  ;; Durability runs mount lazyfs BEFORE the SUT first starts (the storage
  ;; dir must already be the mount when RECOVER opens it) and the mount
  ;; then outlives every kill/restart cycle — only teardown and the
  ;; torn-write remount touch it.
  (setup! [this test node]
    (install! (find-tarball!))
    (when durability? (mount-lazyfs! node))
    (jdb/start! this test node)
    (await-startup! node))

  (teardown! [this test node]
    (jdb/kill! this test node)
    (if durability?
      (do (unmount-lazyfs!)
          (wipe-durability!))
      (wipe!)))

  jdb/LogFiles
  (log-files [_this _test _node]
    (if durability?
      [env/log-file env/lazyfs-log-file]
      [env/log-file]))

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
  "The ratis-kv DB; pass a seed-bug mode name (e.g. \"stale-reads\") to
  start every node with that deliberately seeded SUT bug, and/or a
  retry-cache expiry override in ms (Job 09/Q14 — nil leaves the Ratis
  default), and/or durability? (Job 11/M4 — every node's storage on a
  proven lazyfs mount)."
  ([]
   (db nil))
  ([seed-bug]
   (db seed-bug nil))
  ([seed-bug retry-cache-expiry-ms]
   (db seed-bug retry-cache-expiry-ms false))
  ([seed-bug retry-cache-expiry-ms durability?]
   (RatisKvDB. seed-bug retry-cache-expiry-ms (boolean durability?))))
