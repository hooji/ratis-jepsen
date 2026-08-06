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

(ns ratis-jepsen.db-test
  "Unit tests for the db pure functions (no cluster): command construction,
  the peers spec, tarball selection, the startup-line regex against a
  realistic log line and near-misses — and the env-contract pinning test
  that fails if anyone drifts from DESIGN 2.6."
  (:require [clojure.test :refer [deftest is testing]]
            [ratis-jepsen.db :as db]
            [ratis-jepsen.env-contract :as env])
  (:import (org.apache.ratis.protocol RaftGroupId)))

;; ---------------------------------------------------------------------------
;; env-contract ↔ DESIGN 2.6 pinning: each assertion restates the contract
;; value literally, so drifting from the design breaks a test, loudly.
;; ---------------------------------------------------------------------------

(deftest env-contract-matches-design-2-6
  (is (= ["n1" "n2" "n3" "n4" "n5" "n6" "n7"] env/all-nodes))
  (is (= ["n1" "n2" "n3" "n4" "n5"] env/initial-voters))
  (is (= ["n6" "n7"] env/pool-nodes))
  (is (= "root" env/ssh-user))
  (is (= 6000 env/raft-port))
  (is (= "/opt/ratis-kv" env/install-dir))
  (is (= "/opt/ratis-kv/bin/ratis-kv" env/bin-path))
  (is (= "/opt/ratis-kv/lib" env/lib-dir))
  (is (= "/var/lib/ratis-kv" env/storage-dir))
  (is (= "/var/log/ratis-kv.log" env/log-file))
  (is (= "724d1912-848e-4e0f-a7e0-abbc16e54704" (str env/group-uuid))))

;; ---------------------------------------------------------------------------
;; Command construction
;; ---------------------------------------------------------------------------

(deftest peers-spec-string
  (testing "the contract peers string, in node order"
    (is (= "n1=n1:6000,n2=n2:6000,n3=n3:6000,n4=n4:6000,n5=n5:6000"
           (db/peers-spec env/initial-voters))))
  (testing "arbitrary node subsets keep their order"
    (is (= "n3=n3:6000,n1=n1:6000" (db/peers-spec ["n3" "n1"])))))

(deftest server-args-contract-cli
  (testing "the full contract argv (DESIGN 1.2) for a given node"
    (is (= ["--id" "n3"
            "--peers" "n1=n1:6000,n2=n2:6000,n3=n3:6000,n4=n4:6000,n5=n5:6000"
            "--storage" "/var/lib/ratis-kv"]
           (db/server-args "n3" nil nil))))
  (testing "a seeded-bug run appends the flag (and only then)"
    (is (= ["--id" "n1"
            "--peers" "n1=n1:6000,n2=n2:6000,n3=n3:6000,n4=n4:6000,n5=n5:6000"
            "--storage" "/var/lib/ratis-kv"
            "--seed-bug" "stale-reads"]
           (db/server-args "n1" "stale-reads" nil))))
  (testing "the Q14 expiry override appends its flag (and only then)"
    (is (= ["--id" "n2"
            "--peers" "n1=n1:6000,n2=n2:6000,n3=n3:6000,n4=n4:6000,n5=n5:6000"
            "--storage" "/var/lib/ratis-kv"
            "--retry-cache-expiry-ms" "2000"]
           (db/server-args "n2" nil 2000))))
  (testing "every voter gets the identical peers value"
    (is (apply = (map #(nth (db/server-args % nil nil) 3) env/initial-voters)))))

(deftest join-server-args-contract-cli
  (testing "join mode (Job 08): full 7-node address book + --join"
    (is (= ["--id" "n6"
            "--peers" (db/peers-spec env/all-nodes)
            "--storage" "/var/lib/ratis-kv"
            "--join"]
           (db/join-server-args "n6" nil nil))))
  (testing "seed-bug still appends after --join"
    (is (= ["--id" "n7"
            "--peers" (db/peers-spec env/all-nodes)
            "--storage" "/var/lib/ratis-kv"
            "--join"
            "--seed-bug" "stale-reads"]
           (db/join-server-args "n7" "stale-reads" nil))))
  (testing "the Q14 expiry override rides join mode too"
    (is (= ["--id" "n6"
            "--peers" (db/peers-spec env/all-nodes)
            "--storage" "/var/lib/ratis-kv"
            "--join"
            "--retry-cache-expiry-ms" "1500"]
           (db/join-server-args "n6" nil 1500)))))

(deftest dynamic-node-selection
  (let [test {:membership-state (atom {:voters #{"n1" "n2" "n3" "n4" "n5"}
                                       :pool #{"n6" "n7"}
                                       :dynamic #{"n6" "n7"}})}]
    (testing "with membership state, pool-history nodes start --join"
      (is (db/dynamic-node? test "n6"))
      (is (db/dynamic-node? test "n7"))
      (is (not (db/dynamic-node? test "n1"))))
    (testing "without membership state (non-membership runs), nobody does"
      (is (not (db/dynamic-node? {} "n6")))
      (is (not (db/dynamic-node? {:membership-state nil} "n7"))))))

(deftest conf-line-parsing
  ;; The verbatim shape observed live (SUT JoinModeTest, 2026-08-05;
  ;; ServerState.setRaftConf at ratis-3.2.2) — hostnames abbreviated.
  (let [stable (str "2026-08-05 20:49:18.495 [n1@group-ABBC16E54704-LeaderStateImpl] "
                    "INFO org.apache.ratis.server.RaftServer$Division - "
                    "n1@group-ABBC16E54704: set configuration conf: "
                    "{index: 5, cur=peers:[n1|n1:6000, n2|n2:6000, n4|n4:6000]"
                    "|listeners:[], old=null}")
        transitional (str "n1@group-ABBC16E54704: set configuration conf: "
                          "{index: 3, cur=peers:[n1|n1:6000, n2|n2:6000]"
                          "|listeners:[n7|n7:6000], "
                          "old=peers:[n1|n1:6000]|listeners:[]}")]
    (testing "a stable conf line: index, servers, listeners, stable"
      (is (= {:index 5
              :servers ["n1" "n2" "n4"]
              :listeners []
              :stable? true}
             (db/parse-conf-line stable))))
    (testing "a transitional (old,new) line parses with stable? false"
      (is (= {:index 3
              :servers ["n1" "n2"]
              :listeners ["n7"]
              :stable? false}
             (db/parse-conf-line transitional))))
    (testing "non-conf lines and nil parse to nil"
      (is (nil? (db/parse-conf-line nil)))
      (is (nil? (db/parse-conf-line "n1: changes role from CANDIDATE to LEADER")))
      (is (nil? (db/parse-conf-line "set configuration but not really"))))))

(deftest tarball-selection
  (testing "exactly one match"
    (is (= {:name "ratis-kv-0.1.0-SNAPSHOT.tar.gz" :warning nil}
           (db/select-tarball ["ratis-kv-0.1.0-SNAPSHOT.tar.gz"
                               "ratis-kv-0.1.0-SNAPSHOT.jar"
                               "classes"]))))
  (testing "multiple matches: lexicographically last, with a warning"
    (let [{:keys [name warning]}
          (db/select-tarball ["ratis-kv-0.1.0-SNAPSHOT.tar.gz"
                              "ratis-kv-0.2.0-SNAPSHOT.tar.gz"])]
      (is (= "ratis-kv-0.2.0-SNAPSHOT.tar.gz" name))
      (is (some? warning))))
  (testing "no match throws with build instructions"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"build it first"
                          (db/select-tarball ["ratis-kv-0.1.0-SNAPSHOT.jar"])))))

;; ---------------------------------------------------------------------------
;; The startup-line regex (the boot-await signal, DESIGN 2.6)
;; ---------------------------------------------------------------------------

(def realistic-line
  "A line as the SUT actually logs it: the slf4j-simple layout prefix,
  then the contract payload. group= is rendered via the same
  RaftGroupId.toString() the SUT uses (e.g. group-ABBC16E54704)."
  (str "2026-08-04 13:59:00.123 [main] INFO ratis.jepsen.kv.Main - "
       "ratis-kv server started: id=n1 address=n1:6000 "
       "storage=/var/lib/ratis-kv "
       "group=" (RaftGroupId/valueOf env/group-uuid) " "
       "peers={n1=n1:6000, n2=n2:6000, n3=n3:6000, n4=n4:6000, n5=n5:6000}"))

(deftest startup-regex-matches-the-contract-line
  (testing "the realistic line matches, with all five fields captured"
    (let [[whole id address storage group peers]
          (re-find env/startup-line-pattern realistic-line)]
      (is (some? whole))
      (is (= "n1" id))
      (is (= "n1:6000" address))
      (is (= "/var/lib/ratis-kv" storage))
      (is (= "group-ABBC16E54704" group))
      (is (= "{n1=n1:6000, n2=n2:6000, n3=n3:6000, n4=n4:6000, n5=n5:6000}"
             peers))))
  (testing "matches anywhere in multi-line log content"
    (is (re-find env/startup-line-pattern
                 (str "some earlier line\n" realistic-line "\nlater line")))))

;; ---------------------------------------------------------------------------
;; Leader-census line classification (the crash nemesis's targeting bias)
;; ---------------------------------------------------------------------------

(deftest leader-transition-line-classification
  (let [prefix (str "2026-08-05 04:10:00.123 [nioEventLoopGroup-3-1] INFO "
                    "org.apache.ratis.server.impl.RaftServerImpl - ")]
    (testing "a transition to LEADER counts"
      (is (db/leader-transition?
            (str prefix "n1@group-ABBC16E54704: changes role from CANDIDATE "
                 "to LEADER at term 2 for changeToLeader"))))
    (testing "leadership history does not count — only the destination role"
      (is (not (db/leader-transition?
                 (str prefix "n1@group-ABBC16E54704: changes role from LEADER "
                      "to FOLLOWER at term 3 for stepDown"))))
      (is (not (db/leader-transition?
                 (str prefix "n2@group-ABBC16E54704: changes role from FOLLOWER "
                      "to CANDIDATE at term 4 for changeToCandidate")))))
    (testing "nil (no transition line in the log) is not a leader"
      (is (not (db/leader-transition? nil))))))

(deftest startup-regex-rejects-near-misses
  (let [prefix "2026-08-04 13:59:00.123 [main] INFO ratis.jepsen.kv.Main - "]
    (doseq [[why line]
            [["a 'starting' line, not 'started'"
              (str prefix "ratis-kv server starting: id=n1 address=n1:6000 "
                   "storage=/var/lib/ratis-kv group=group-ABBC16E54704 "
                   "peers={n1=n1:6000}")]
             ["missing the peers field"
              (str prefix "ratis-kv server started: id=n1 address=n1:6000 "
                   "storage=/var/lib/ratis-kv group=group-ABBC16E54704")]
             ["missing the group field"
              (str prefix "ratis-kv server started: id=n1 address=n1:6000 "
                   "storage=/var/lib/ratis-kv "
                   "peers={n1=n1:6000}")]
             ["fields out of order"
              (str prefix "ratis-kv server started: address=n1:6000 id=n1 "
                   "storage=/var/lib/ratis-kv group=group-ABBC16E54704 "
                   "peers={n1=n1:6000}")]
             ["empty id value"
              (str prefix "ratis-kv server started: id= address=n1:6000 "
                   "storage=/var/lib/ratis-kv group=group-ABBC16E54704 "
                   "peers={n1=n1:6000}")]
             ["empty peers value"
              (str prefix "ratis-kv server started: id=n1 address=n1:6000 "
                   "storage=/var/lib/ratis-kv group=group-ABBC16E54704 peers=")]
             ["a different server's startup line"
              (str prefix "other-kv server started: id=n1 address=n1:6000 "
                   "storage=/var/lib/ratis-kv group=group-ABBC16E54704 "
                   "peers={n1=n1:6000}")]]]
      (testing why
        (is (nil? (re-find env/startup-line-pattern line)) line)))))
