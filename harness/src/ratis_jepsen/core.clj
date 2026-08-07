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
              membership|membership-snapshot-churn|listener-probe|mixed-all
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

                               Membership-bearing kinds run the full
                               7-node topology: the harness overrides
                               --nodes with the contract node list, so
                               run.sh's five-voter default keeps working
                               unchanged.
    --reads leader|follower|mixed
                               where linearizable reads go (default
                               leader; follower exercises
                               sendReadOnly(msg, peerId))
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
  (:require [jepsen.cli :as cli]
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

   [nil "--key-count NUMBER" "How many independent register keys to run through"
    :default 5
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

(defn workload-defaults
  "Fills :rate and :ops-per-key when the CLI left them unset — per kind:
  the combined membership-snapshot-churn kind inherits the Job 07 churn
  numbers (rate 1.4, ops-per-key 800 — the sustained write stream that
  crosses the server's purge.gap=1024 milestones), and the M3 counter
  workload gets the same sustained stream for a different reason — its
  retry-evidence law needs the op phase to overlap many leader-kill
  windows, and CI dispatches scenarios with no extra flags (a
  default-rate counter run would burn its budget in ~25 s and could
  legally see zero retries). Every other combination keeps the M0
  defaults (10.0, 300). Explicit CLI values always win."
  [opts]
  (let [sustained? (or (= "membership-snapshot-churn" (:nemesis opts))
                       (= "counter" (:workload opts)))
        ;; unsync-drop-all takes the WHOLE cluster down every cycle, so a
        ;; quarter of the run is a total outage and the ambiguous-write
        ;; (:info) count is inherently high — 122 in the first gate run,
        ;; which pushed knossos past its documented memory cliff
        ;; (DESIGN 6; Job 07 hit the same wall at 147). DESIGN 2.5's
        ;; sanctioned lever is to shrink the per-key history and say so,
        ;; which is what this does; the fault, its evidence and the
        ;; safety question are all unchanged.
        all-down?  (= "unsync-drop-all" (:nemesis opts))]
    (-> opts
        (update :rate        #(or % (if sustained? 1.4 10.0)))
        (update :ops-per-key #(or % (cond sustained? 800
                                          all-down?  150
                                          :else      300))))))

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
        ;; M4: durability kinds turn every node's storage dir into a
        ;; lazyfs mount. Nothing else in the harness changes shape, and
        ;; a non-durability run never touches lazyfs at all.
        durability  (when (contains? nemesis/durability-kinds (:nemesis opts))
                      (cond-> {}
                        ;; Armed on REMOUNT only (see
                        ;; db/torn-write-occurrence): the first cycle
                        ;; runs clean so the tear lands on a log that
                        ;; already holds committed entries.
                        (= "torn-write" (:nemesis opts))
                        (assoc :injection (db/torn-write-injection)
                               :arm-on-remount-only? true)))
        nodes       (if membership?
                      (vec env/all-nodes)
                      (:nodes opts))
        workload    ((workloads (:workload opts)) opts)
        nem         (nemesis/package (:nemesis opts) opts)]
    (merge tests/noop-test
           opts
           {:name      (str "ratis-kv-" (:workload opts)
                            "-" (:nemesis opts)
                            (when (:seed-bug opts)
                              (str "-seedbug-" (:seed-bug opts))))
            :nodes     nodes
            :db        (db/db {:seed-bug (:seed-bug opts)
                               :retry-cache-expiry-ms (:retry-cache-expiry-ms opts)
                               :durability durability})
            :client    (client/client)
            :nemesis   (:nemesis nem)
            :checker   (:checker workload)
            :generator (->> (:generator workload)
                            (gen/nemesis (:generator nem))
                            (gen/time-limit (:time-limit opts)))}
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
