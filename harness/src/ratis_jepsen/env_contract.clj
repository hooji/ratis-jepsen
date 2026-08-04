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

(ns ratis-jepsen.env-contract
  "The deployment contract (DESIGN 2.6), pinned 2026-08-04, in one place.

  env/ (Job 02) and the harness both build to these values; nothing else in
  the harness may restate them. Changing any value here is a breaking change
  to the deployment contract and requires a coordinator brief.")

;; Nodes: n1..n7; n1..n5 are the initial voters, n6/n7 the dormant
;; membership pool (PLAN Q5). M0 runs no SUT on n6/n7.
(def all-nodes
  "Every node in the topology, including the dormant pool."
  ["n1" "n2" "n3" "n4" "n5" "n6" "n7"])

(def initial-voters
  "The five voters of the raft group, from first boot."
  ["n1" "n2" "n3" "n4" "n5"])

(def pool-nodes
  "Dormant membership-pool nodes; provisioned but running no SUT in M0."
  ["n6" "n7"])

(def ssh-user
  "All remote operations run as this user (passwordless ssh from control)."
  "root")

(def raft-port
  "The raft/gRPC port, identical on every node."
  6000)

(def install-dir
  "Where the SUT tarball is unpacked on each db node."
  "/opt/ratis-kv")

(def bin-path
  "The launcher inside the unpacked tarball."
  (str install-dir "/bin/ratis-kv"))

(def lib-dir
  "The jar directory inside the unpacked tarball."
  (str install-dir "/lib"))

(def storage-dir
  "The raft storage directory on each db node (--storage)."
  "/var/lib/ratis-kv")

(def log-file
  "Where each node's stdout is captured."
  "/var/log/ratis-kv.log")

(def startup-line-pattern
  "Matches the SUT's boot-await line (DESIGN 2.6), used with re-find
  against log content. After RaftServer.start() the SUT's stdout emits

    ratis-kv server started: id=<id> address=<host:port> storage=<dir> \\
      group=<uuid> peers=<list>

  (one line; produced by sut Main.java, confirmed present at Job 01's
  merge). The five fields are captured in order. The pattern is
  deliberately strict about field names, order and non-emptiness so that
  near-misses (a 'starting' line, a field missing or re-ordered) do not
  pass; it does not anchor at line start because the slf4j-simple layout
  prefixes a timestamp and logger name."
  #"ratis-kv server started: id=(\S+) address=(\S+) storage=(\S+) group=(\S+) peers=(\S.*)")

(def group-uuid
  "The fixed raft group id, compiled into the SUT binary and identical on
  every peer. Not part of the DESIGN 2.6 table, but contract-pinned all the
  same: copied from Main.GROUP_UUID in
  sut/ratis-kv/src/main/java/ratis/jepsen/kv/Main.java — the client must
  join exactly this group. Keep in sync with the SUT source."
  (java.util.UUID/fromString "724d1912-848e-4e0f-a7e0-abbc16e54704"))
