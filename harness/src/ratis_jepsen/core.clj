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

(ns ratis-jepsen.core
  "CLI entry and test-map assembly (DESIGN 2.1).

  M2 shape: the register workload over independent keys, a fault schedule
  chosen by --nemesis, knossos linearizability checking per key plus the
  whole-history liveness checker (and, per kind, the evidence checkers:
  install-snapshot for snapshot-churn, committed-conf-change for
  membership, joiner-install for the combined kind).
  `clojure -M:run test --help` for the options; the interesting ones:

    --workload register|counter
                               register = the M0 r/w/cas linearizability
                               workload; counter = the M3 exactly-once
                               increment workload (known-delta :add ops,
                               per-key counter bounds checking, retry
                               evidence law)
    --nemesis none|partition|crash|pause|mixed|snapshot-churn|transfer|
              membership|membership-snapshot-churn|listener-probe|mixed-all|
              unsync-drop|unsync-drop-all|torn-write|rolling-upgrade
                               fault schedule (default none); crash =
                               kill -9/restart of a leader-biased random
                               minority, pause = SIGSTOP/SIGCONT,
                               snapshot-churn = kill a follower + snapshot
                               and purge live servers + restart it
                               (forcing install-snapshot), transfer =
                               periodic leadership transfer, membership =
                               randomized add/remove/replace-dead over the
                               n6/n7 pool (voter band 5±2, floor 3),
                               membership-snapshot-churn = membership and
                               churn segments interleaved (joins must
                               catch up via install-snapshot),
                               listener-probe = the bounded scripted
                               listener-staging sequence, mixed = the M1
                               three, mixed-all = all six fault kinds
                               (durability kinds stay out — opt-in
                               topology, Job 10 recommendation)

                               Durability kinds (Job 11, M4; all three
                               force --durability on): unsync-drop =
                               kill -9 a random minority and drop each
                               one's un-fsynced storage, restart;
                               unsync-drop-all = the same power loss on
                               EVERY voter at once; torn-write = tear
                               one follower's next log append mid-write
                               (lazyfs torn-op: only the head of the
                               write persists) and record whether the
                               node recovers or refuses loudly.

                               Membership-bearing kinds run the full
                               7-node topology: the harness overrides
                               --nodes with the contract node list, so
                               run.sh's five-voter default keeps working
                               unchanged.
    --durability               storage-durability topology: every node's
                               raft storage becomes a lazyfs FUSE mount
                               (proven per node; a run that cannot prove
                               its mounts fails loudly). Forced on by
                               the durability nemeses; legal with any
                               other nemesis for regression comparison.
    --reads leader|follower|mixed
                               where linearizable reads go (default
                               leader; follower exercises
                               sendReadOnly(msg, peerId))
    --ratis-version V          the Ratis version under test (default
                               3.2.2): selects the SUT tarball
                               ratis-kv-*-ratis-V.tar.gz; env/run.sh
                               injects the matching ratis-client into
                               this JVM and ratis-test refuses to run on
                               a skewed classpath (Job 12, M5)
    --mixed-version OLD,NEW    mixed-version topology (Job 12): both
                               versions installed on every node; static
                               kinds split voters ceil(n/2) OLD / rest
                               NEW, rolling-upgrade starts all-OLD and
                               each :roll moves one voter to NEW; the
                               harness client runs OLD (clients upgrade
                               last). Supported kinds:
                               none|partition|crash|pause|mixed|transfer|
                               rolling-upgrade
    --time-limit 300           wall-clock ceiling; the op budget usually
                               exhausts first
    --key-count 5 --ops-per-key 400 --concurrency 10   the DESIGN 2.5
                               knossos budget
    --rate N                   ops/s per worker. Defaults are per kind:
                               10 (the M0 behavior) everywhere except
                               membership-snapshot-churn, which defaults
                               to the Job 07 churn numbers (rate 1.4,
                               ops-per-key 800) so its write stream
                               crosses the purge-gap milestones without
                               extra flags — CI passes only --nemesis and
                               --time-limit
    --seed-bug stale-reads     start every node with the SUT's seeded bug
                               (the red-run lever; testing the harness)
    --store-dir DIR            where jepsen's store/ output lands"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jepsen.cli :as cli]
            [jepsen.generator :as gen]
            [jepsen.store :as store]
            [jepsen.tests :as tests]
            [ratis-jepsen.client :as client]
            [ratis-jepsen.db :as db]
            [ratis-jepsen.env-contract :as env]
            [ratis-jepsen.nemesis :as nemesis]
            [ratis-jepsen.workload.counter :as counter]
            [ratis-jepsen.workload.register :as register]))

(def workloads
  "Workload name -> (fn [opts] {:generator, :checker})."
  {"register" register/workload
   "counter"  counter/workload})

(def cli-opts
  "Additional CLI options, merged over jepsen's test-opt-spec (same
  option string = replaces jepsen's entry, which is how --concurrency
  and --time-limit get M0-budget defaults)."
  [[nil "--workload NAME" "Workload to run"
    :default "register"
    :validate [workloads (cli/one-of workloads)]]

   [nil "--nemesis NAME" "Fault schedule during the run"
    :default "none"
    :validate [nemesis/kinds (cli/one-of nemesis/kinds)]]

   ;; Crash/pause cycle knobs (the brief's "configurable cycle"); defaults
   ;; live in ratis-jepsen.nemesis. The partition cycle is pinned (Job 04).
   [nil "--crash-calm-s SECONDS" "Crash cycle: calm stretch before each kill"
    :default (:calm-s nemesis/default-crash-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--crash-fault-s SECONDS" "Crash cycle: how long nodes stay killed"
    :default (:fault-s nemesis/default-crash-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--pause-calm-s SECONDS" "Pause cycle: running stretch before each SIGSTOP"
    :default (:calm-s nemesis/default-pause-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--pause-fault-s SECONDS" "Pause cycle: how long processes stay stopped"
    :default (:fault-s nemesis/default-pause-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--churn-calm-s SECONDS" "Snapshot-churn cycle: calm stretch before each follower kill"
    :default (:calm-s nemesis/default-churn-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--churn-kill-to-transfer-s SECONDS" "Snapshot-churn cycle: gap between the kill and the leadership transfer (the term bump that makes the purge real)"
    :default (:kill-to-transfer-s nemesis/default-churn-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--churn-transfer-to-snapshot-s SECONDS" "Snapshot-churn cycle: writes window between the transfer and the snapshot trigger"
    :default (:transfer-to-snapshot-s nemesis/default-churn-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--churn-snapshot-to-restart-s SECONDS" "Snapshot-churn cycle: gap between the snapshot trigger and the follower restart"
    :default (:snapshot-to-restart-s nemesis/default-churn-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--transfer-calm-s SECONDS" "Transfer cycle: gap between leadership transfers"
    :default (:calm-s nemesis/default-transfer-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--membership-calm-s SECONDS" "Membership cycle: calm stretch before each move"
    :default (:calm-s nemesis/default-membership-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--membership-replace-dead-s SECONDS" "Membership cycle: how long a replace-dead's victim stays dead-and-removed before the replacement half runs"
    :default (:replace-dead-s nemesis/default-membership-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--membership-min-conf-changes NUMBER" "Evidence floor for dedicated membership runs: at least this many committed configuration changes must appear in the node logs"
    :default 2
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--reads MODE" "Where linearizable reads are sent: leader (sendReadOnly, M0 behavior), follower (sendReadOnly to a non-leader peer), or mixed (50/50)"
    :default "leader"
    :validate [#{"leader" "follower" "mixed"} "Must be: leader, follower or mixed"]]

   ;; No cli-level default: the effective default is per nemesis kind
   ;; (workload-defaults) — 10.0 everywhere except the combined
   ;; membership-snapshot-churn kind, whose runs need the Job 07 churn
   ;; write stream without extra flags (CI passes only --nemesis and
   ;; --time-limit).
   [nil "--rate OPS-PER-SECOND" "Approximate ops per second per worker (default 10, the M0 behavior; membership-snapshot-churn defaults to 1.4 — the sustained stream that crosses purge-gap milestones)"
    :parse-fn #(Double/parseDouble %)
    :validate [pos? "Must be positive"]]

   ;; No cli-level default: per kind (workload-defaults) — 5 everywhere
   ;; (the M0 budget) except unsync-drop-all, which spreads the same
   ;; concurrency over 10 keys so each key runs ONE worker (the knossos
   ;; :info budget under whole-cluster outages; see nemesis).
   [nil "--key-count NUMBER" "How many independent keys to run through (default 5; unsync-drop-all 10 — one worker per key)"
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   ;; Default shrunk from the brief's 400 after a reference run's key
   ;; history (400 ops with ~40 partition-:info) blew knossos's memory —
   ;; the sanctioned lever ("if analysis exceeds the budget, shrink
   ;; ops-per-key and say so"); DESIGN 2.5 pins only <=400. Like --rate,
   ;; the default is per kind: 300 everywhere except
   ;; membership-snapshot-churn (800, the Job 07 churn budget).
   [nil "--ops-per-key NUMBER" "Hard cap on ops per key (the knossos budget; default 300, membership-snapshot-churn 800)"
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--retry-delay-ms MILLIS" "Client-side delay between same-callId retry attempts (default 200, the M1-established policy). The Q14 expiry run raises it above the shrunken server retry-cache window so the retry arrives after the entry expired; the harness invocation deadline widens automatically to cover the full attempt span."
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--retry-cache-expiry-ms MILLIS" "Start every node with raft.server.retrycache.expirytime overridden to this (absent: the Ratis default 60 s stays untouched). Q14 test lever only — shrinking it below the client's total retry span re-arms the documented retry double-apply boundary, so a run with this flag can be red BY DESIGN."
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--seed-bug MODE" "Start every node with a deliberately seeded SUT bug (stale-reads). Testing the harness only — a run with this flag is expected to FAIL its checker."
    :validate [#{"stale-reads"} "Must be: stale-reads"]]

   ;; --- version matrix (Job 12, M5) ---------------------------------------
   ;; The version selects which SUT tarball db.clj installs
   ;; (ratis-kv-*-ratis-<version>.tar.gz). The harness JVM's OWN
   ;; ratis-client/-grpc deps are fixed at launch — env/run.sh injects the
   ;; matching versions via -Sdeps :override-deps from the same flag —
   ;; and ratis-test refuses to run when the classpath disagrees with the
   ;; requested version (the version-skew guard).
   [nil "--ratis-version VERSION" "The Ratis version under test: which SUT tarball to install (and, via env/run.sh, which ratis-client the harness itself runs)"
    :default db/default-ratis-version
    :validate [#(re-matches #"[0-9A-Za-z._-]+" %)
               "Must be a plain version token, e.g. 3.2.2"]]

   [nil "--mixed-version OLD,NEW" "Mixed-version topology: install BOTH versions on every node; static kinds split the voters old/new (first ceil(n/2) nodes old), rolling-upgrade starts all-old and rolls each voter to NEW during the run. The harness client runs OLD (clients upgrade last). Overrides --ratis-version."
    :parse-fn (fn [s] (mapv str/trim (str/split s #"," 3)))
    :validate [#(and (= 2 (count %))
                     (every? (fn [v] (re-matches #"[0-9A-Za-z._-]+" v)) %)
                     (apply not= %))
               "Must be two distinct version tokens: OLD,NEW"]]

   [nil "--roll-calm-s SECONDS" "Rolling upgrade: calm all-old stretch before the first roll"
    :default (:calm-s nemesis/default-rolling-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--roll-gap-s SECONDS" "Rolling upgrade: settle gap after each roll"
    :default (:gap-s nemesis/default-rolling-cycle)
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]

   [nil "--durability" "Mount every node's raft storage as lazyfs (Job 11/M4 storage-durability topology), proving the mount per node and failing the run loudly if it cannot. Forced on by the durability nemeses (unsync-drop, unsync-drop-all, torn-write)."]

   [nil "--torn-persist-part PART" "torn-write only: which third of the torn write reaches the backing store. 1 (default) keeps the head — the truest sequential power loss, biasing recovery toward clean tail truncation; 2 keeps a middle fragment behind a zero hole, biasing recovery toward the loud CorruptionPolicy=EXCEPTION refusal. Both outcomes are legal and recorded."
    :default 1
    :parse-fn #(Long/parseLong %)
    :validate [#{1 2 3} "Must be 1, 2 or 3 (the torn write has 3 parts)"]]

   [nil "--store-dir DIR" "Directory jepsen writes its store/ results under"
    :default "store"]

   ;; Overrides of jepsen defaults for the DESIGN 2.5 budget; option
   ;; strings must match jepsen's exactly for merge-opt-specs to replace.
   [nil "--concurrency NUMBER" "How many workers should we run? Must be an integer, optionally followed by n (e.g. 3n) to multiply by the number of nodes."
    :default "10"
    :validate [(partial re-find #"^\d+n?$")
               "Must be an integer, optionally followed by n."]]

   [nil "--time-limit SECONDS"
    "Excluding setup and teardown, how long should a test run for, in seconds?"
    :default 300
    :parse-fn #(Long/parseLong %)
    :validate [pos? "Must be positive"]]])

;; ---------------------------------------------------------------------------
;; Version matrix (Job 12, M5)
;; ---------------------------------------------------------------------------

(defn classpath-ratis-client-version
  "The ratis-client version actually on this JVM's classpath, parsed
  from the Maven-repo jar path (…/ratis-client/<v>/ratis-client-<v>.jar),
  or nil when no such jar is found (e.g. a source-tree classpath)."
  ([] (classpath-ratis-client-version
        (System/getProperty "java.class.path")
        (System/getProperty "path.separator")))
  ([classpath separator]
   (some #(second (re-find #"ratis-client-([0-9][^/\\]*)\.jar$" %))
         (str/split (or classpath "")
                    (re-pattern (java.util.regex.Pattern/quote separator))))))

(defn check-client-version!
  "The version-skew guard: the harness's own ratis-client dependency must
  match the server under test — the run's --ratis-version, or the OLD
  half of a mixed pair (rolling-upgrade convention: clients upgrade
  last). env/run.sh injects the right client via -Sdeps :override-deps;
  this refuses to run when that didn't happen, because a skewed client
  would silently test the wrong client stack. Returns the classpath
  version (nil, with a loud warning, when no jar is recognizable)."
  [expected]
  (let [actual (classpath-ratis-client-version)]
    (cond
      (nil? actual)
      (log/warn "cannot determine the classpath ratis-client version —"
                "proceeding, but the harness client is unverified"
                "(expected" expected ")")

      (not= actual expected)
      (throw (ex-info
               (str "version skew: this JVM runs ratis-client " actual
                    " but the run wants " expected
                    " — launch through env/run.sh test (it injects matching"
                    " client deps via -Sdeps), or pass a matching"
                    " --ratis-version/--mixed-version")
               {:classpath-ratis-client actual
                :expected expected})))
    actual))

(def mixed-version-kinds
  "The fault schedules a --mixed-version run supports: the wire-compat
  set (Job 12 deliverable 4). Everything else is refused loudly — the
  membership kinds move nodes through pool wipes and --join bootstraps
  that the per-node version symlinks have never been exercised against,
  and the durability kinds add a storage stack whose interaction with
  mid-run version flips is untested."
  #{"none" "partition" "crash" "pause" "mixed" "transfer"
    "rolling-upgrade"})

(defn initial-version-map
  "Node -> starting Ratis version for a run. Single-version: everything
  at ratis-version. Mixed static split: the first ceil(n/2) nodes (in
  the given order) run OLD, the rest NEW — majority-old, so both
  old-leader→new-follower and (after elections/transfers)
  new-leader→old-follower appends occur. Rolling upgrade: every node
  starts OLD; the :roll ops move them to NEW one at a time."
  [nodes ratis-version mixed-versions rolling?]
  (let [nodes (vec nodes)]
    (cond
      (nil? mixed-versions)
      (zipmap nodes (repeat ratis-version))

      rolling?
      (zipmap nodes (repeat (first mixed-versions)))

      :else
      (let [[old new] mixed-versions
            n-old     (Math/ceil (/ (count nodes) 2.0))]
        (into {}
              (map-indexed (fn [i node]
                             [node (if (< i n-old) old new)])
                           nodes))))))

(defn workload-defaults
  "Fills :rate and :ops-per-key when the CLI left them unset — per kind:
  the combined membership-snapshot-churn kind inherits the Job 07 churn
  numbers (rate 1.4, ops-per-key 800 — the sustained write stream that
  crosses the server's purge.gap=1024 milestones), and the M3 counter
  workload gets the same sustained stream for a different reason — its
  retry-evidence law needs the op phase to overlap many leader-kill
  windows, and CI dispatches scenarios with no extra flags (a
  default-rate counter run would burn its budget in ~25 s and could
  legally see zero retries). unsync-drop-all (Job 11) gets rate 0.5 for
  the knossos :info budget: every write invoked during a whole-cluster
  outage is honestly ambiguous and stays forever-concurrent in the
  linear checker, so the in-flight mass per outage window must stay
  small (the calm-25 s/rate-10 shakedown OOMed analysis on every key —
  see nemesis/unsync-drop-all-cycle); at 0.5 the default 300-op budget
  also exactly spans a 300 s run. Every other combination keeps the M0
  defaults (10.0, 300, 5 keys). Explicit CLI values always win.

  unsync-drop-all additionally defaults :key-count to 10: with the
  shared concurrency 10 that is ONE worker per key, halving each key's
  forever-concurrent :info mass (a thread yields one ambiguous write
  per ~5 s of total outage — the invocation timeout — no matter the
  rate); the 0.5 rate makes the 300-op budget span the full 300 s
  instead of exhausting before the first outage."
  [opts]
  (let [sustained? (or (= "membership-snapshot-churn" (:nemesis opts))
                       (= "counter" (:workload opts)))
        drop-all?  (= "unsync-drop-all" (:nemesis opts))]
    (-> opts
        (update :rate        #(or % (cond sustained? 1.4
                                          drop-all?  0.5
                                          :else      10.0)))
        (update :ops-per-key #(or % (if sustained? 800 300)))
        (update :key-count   #(or % (if drop-all? 10 5))))))

(defn ratis-test
  "Builds the test map from parsed CLI options: register workload +
  chosen nemesis over the db/client/outcome stack from Job 03.

  Membership-bearing kinds (nemesis/membership-kinds) get two additions:
  :nodes is overridden to the full DESIGN 2.6 topology — the dormant
  n6/n7 pool exists for exactly this, and run.sh's pinned five-voter
  --nodes must keep working for every other kind — and the test map
  carries the :membership-state atom that the membership nemesis, the
  db layer (start mode per node) and fault targeting share."
  [opts]
  ;; jepsen.store writes under its base-dir var; point it wherever the
  ;; caller asked (env/run.sh test passes /ratis-jepsen/store so results
  ;; land on the bind mount outside the container).
  (alter-var-root #'store/base-dir (constantly (:store-dir opts)))
  (let [opts        (workload-defaults opts)
        membership? (contains? nemesis/membership-kinds (:nemesis opts))
        ;; The durability kinds force the lazyfs topology on — an
        ;; un-synced-drop against the plain filesystem would silently
        ;; test nothing (the evidence law); --durability alone composes
        ;; the mount under any other schedule.
        durability-kind? (contains? nemesis/durability-kinds (:nemesis opts))
        durability? (boolean (or (:durability opts) durability-kind?))
        opts        (assoc opts :durability durability?)
        ;; Version matrix (Job 12): a mixed pair overrides the single
        ;; version; the harness's own client must match the single
        ;; version / the pair's OLD half (clients upgrade last), which
        ;; check-client-version! enforces against the real classpath.
        mixed       (:mixed-version opts)
        rolling?    (= "rolling-upgrade" (:nemesis opts))
        _           (when (and rolling? (not mixed))
                      (throw (ex-info (str "--nemesis rolling-upgrade needs "
                                           "--mixed-version OLD,NEW — a roll "
                                           "with one version is just a "
                                           "restart")
                                      {:nemesis (:nemesis opts)})))
        _           (when (and mixed
                               (not (contains? mixed-version-kinds
                                               (:nemesis opts))))
                      (throw (ex-info (str "--mixed-version supports nemeses "
                                           (str/join "|" (sort mixed-version-kinds))
                                           " only (got " (:nemesis opts) ")")
                                      {:nemesis (:nemesis opts)})))
        _           (when (and mixed durability?)
                      (throw (ex-info (str "--mixed-version cannot compose "
                                           "with the lazyfs durability "
                                           "topology (untested interaction)")
                                      {})))
        client-version (if mixed (first mixed) (:ratis-version opts))
        harness-client (check-client-version! client-version)
        nodes       (if membership?
                      (vec env/all-nodes)
                      (:nodes opts))
        workload    ((workloads (:workload opts)) opts)
        nem         (nemesis/package (:nemesis opts) opts)]
    (merge tests/noop-test
           opts
           {:name      (str "ratis-kv-" (:workload opts)
                            "-" (:nemesis opts)
                            ;; the version under test is part of the run's
                            ;; identity (the matrix ledger keys on it)
                            (if mixed
                              (str "-mixed-" (first mixed) "-" (second mixed))
                              (str "-ratis-" (:ratis-version opts)))
                            ;; durability-kind names already say it
                            (when (and durability? (not durability-kind?))
                              "-durability")
                            (when (:seed-bug opts)
                              (str "-seedbug-" (:seed-bug opts))))
            :nodes     nodes
            :db        (db/db (:seed-bug opts) (:retry-cache-expiry-ms opts)
                              durability? (:ratis-version opts) mixed)
            :client    (client/client)
            :nemesis   (:nemesis nem)
            :checker   (:checker workload)
            :generator (->> (:generator workload)
                            (gen/nemesis (:generator nem))
                            (gen/time-limit (:time-limit opts)))
            ;; recorded into the store: what the harness JVM actually ran
            :harness-ratis-client harness-client}
           (when mixed
             {:mixed-versions mixed
              :version-state  (atom (initial-version-map
                                      nodes (:ratis-version opts)
                                      mixed rolling?))})
           (when membership?
             {:membership-state
              (atom (nemesis/initial-membership-state
                      env/all-nodes env/initial-voters))}))))

(defn -main
  [& args]
  ;; :usage passed as a string — jepsen 0.3.13's default hands the
  ;; test-usage fn itself to the printer, which renders as #object[...].
  (cli/run! (cli/single-test-cmd {:test-fn  ratis-test
                                  :opt-spec cli-opts
                                  :usage    (cli/test-usage)})
            args))
