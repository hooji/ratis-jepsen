#!/usr/bin/env bash
#
# Copyright 2026 the ratis-jepsen authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# End-to-end proof of the environment (Job 02, deliverable 5): brings the
# topology up, builds the SUT tarball inside control, installs and starts a
# 5-voter cluster on n1..n5 per the deployment contract (DESIGN 2.6), then
# asserts:
#   (a) the contract startup line appears in all five logs;
#   (b) exactly one node is *currently* leader: per node, only the last
#       Ratis role-transition line counts, and it must be a transition to
#       LEADER — election history (an early re-election) does not fail a
#       healthy cluster;
#   (c) port 6000 is listening on all five nodes;
#   (d) SIGTERM stops all five servers cleanly.
# Exits 0 only if every check holds, printing each check's evidence line.
set -euo pipefail

ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERVERS=(n1 n2 n3 n4 n5)
PEERS="n1=n1:6000,n2=n2:6000,n3=n3:6000,n4=n4:6000,n5=n5:6000"
INSTALL_DIR=/opt/ratis-kv
STORAGE_DIR=/var/lib/ratis-kv
LOG_FILE=/var/log/ratis-kv.log
PID_FILE=/var/run/ratis-kv.pid
STARTUP_LINE='ratis-kv server started:'
LEADER_PATTERN='changes role from .* to LEADER'

STARTUP_DEADLINE="${RJ_STARTUP_DEADLINE:-90}"
LEADER_DEADLINE="${RJ_LEADER_DEADLINE:-90}"
LEADER_SETTLE="${RJ_LEADER_SETTLE:-5}"
STOP_DEADLINE="${RJ_STOP_DEADLINE:-30}"

compose() {
  docker compose -f "${ENV_DIR}/docker-compose.yml" "$@"
}

in_control() {
  compose exec -T control "$@"
}

on_node() {
  local node=$1
  shift
  in_control ssh "root@${node}" "$@"
}

say() {
  echo "validate: $*"
}

fail() {
  echo "validate: FAIL: $*" >&2
  exit 1
}

dump_log_tail() {
  local node=$1
  echo "--- last lines of ${node}:${LOG_FILE} ---" >&2
  on_node "${node}" "tail -n 20 ${LOG_FILE} 2>/dev/null" >&2 || true
}

# --- bring the environment up ---------------------------------------------
say "step: run.sh up"
"${ENV_DIR}/run.sh" up

# --- build the SUT tarball inside control ----------------------------------
say "step: build SUT tarball inside control"
in_control /ratis-jepsen/sut/ratis-kv/mvnw \
  -f /ratis-jepsen/sut/ratis-kv/pom.xml -q package
TARBALL=$(in_control bash -c \
  "ls /ratis-jepsen/sut/ratis-kv/target/ratis-kv-*.tar.gz" | tr -d '\r')
[[ -n "${TARBALL}" ]] || fail "no tarball produced by the SUT build"
say "built ${TARBALL}"

# --- install and start on n1..n5 -------------------------------------------
say "step: install tarball at ${INSTALL_DIR} on ${SERVERS[*]}"
for node in "${SERVERS[@]}"; do
  on_node "${node}" "rm -rf ${INSTALL_DIR} ${STORAGE_DIR} ${LOG_FILE} ${PID_FILE} \
    && mkdir -p ${INSTALL_DIR} ${STORAGE_DIR}"
  in_control scp -q "${TARBALL}" "root@${node}:/tmp/ratis-kv.tar.gz"
  on_node "${node}" "tar -xzf /tmp/ratis-kv.tar.gz -C ${INSTALL_DIR} \
    && rm /tmp/ratis-kv.tar.gz && test -x ${INSTALL_DIR}/bin/ratis-kv"
done

say "step: start servers (stdout -> ${LOG_FILE})"
for node in "${SERVERS[@]}"; do
  on_node "${node}" "nohup ${INSTALL_DIR}/bin/ratis-kv \
      --id ${node} --peers ${PEERS} --storage ${STORAGE_DIR} \
      > ${LOG_FILE} 2>&1 & echo \$! > ${PID_FILE}"
done

# --- (a) contract startup line in all five logs ----------------------------
say "check (a): startup line in all five logs (deadline ${STARTUP_DEADLINE}s)"
for node in "${SERVERS[@]}"; do
  deadline=$((SECONDS + STARTUP_DEADLINE))
  until on_node "${node}" "grep -q '${STARTUP_LINE}' ${LOG_FILE} 2>/dev/null"; do
    if ((SECONDS >= deadline)); then
      dump_log_tail "${node}"
      fail "(a) startup line not seen on ${node} within ${STARTUP_DEADLINE}s"
    fi
    sleep 1
  done
  say "  [${node}] $(on_node "${node}" "grep -m1 '${STARTUP_LINE}' ${LOG_FILE}")"
done
say "PASS (a): startup line present on all five nodes"

# --- (b) exactly one current leader ----------------------------------------
say "check (b): exactly one current leader (deadline ${LEADER_DEADLINE}s)"
# A node counts as leader iff the LAST role-transition line in its log is a
# transition to LEADER — current leadership, not election history. A node
# that lost leadership has a later 'LEADER to FOLLOWER' line and no longer
# counts.
last_transition() {
  on_node "$1" "grep 'changes role from' ${LOG_FILE} 2>/dev/null | tail -n 1" \
    || true
}
count_leaders() {
  LEADERS=()
  local node
  for node in "${SERVERS[@]}"; do
    if [[ "$(last_transition "${node}")" == *"to LEADER"* ]]; then
      LEADERS+=("${node}")
    fi
  done
}
deadline=$((SECONDS + LEADER_DEADLINE))
while :; do
  count_leaders
  ((${#LEADERS[@]} > 0)) && break
  if ((SECONDS >= deadline)); then
    for node in "${SERVERS[@]}"; do dump_log_tail "${node}"; done
    fail "(b) no leader observed within ${LEADER_DEADLINE}s"
  fi
  sleep 1
done
sleep "${LEADER_SETTLE}"
# Re-sample until the census reads exactly one: a sample taken mid-handover
# may legitimately read 0 or 2; only the deadline makes that a failure.
while :; do
  count_leaders
  ((${#LEADERS[@]} == 1)) && break
  if ((SECONDS >= deadline)); then
    for node in "${SERVERS[@]}"; do
      say "  [${node}] last role transition: $(last_transition "${node}")"
    done
    fail "(b) expected exactly one current leader at the deadline," \
         "found ${#LEADERS[@]}: ${LEADERS[*]:-none}"
  fi
  sleep 1
done
say "  [${LEADERS[0]}] $(on_node "${LEADERS[0]}" \
  "grep '${LEADER_PATTERN}' ${LOG_FILE} | tail -n 1")"
say "PASS (b): exactly one node (${LEADERS[0]}) is currently LEADER"

# --- (c) port 6000 listening on all five nodes -----------------------------
say "check (c): port 6000 listening on all five nodes"
for node in "${SERVERS[@]}"; do
  line=$(on_node "${node}" "ss -ltn | grep ':6000 '") \
    || { dump_log_tail "${node}"; fail "(c) ${node} is not listening on 6000"; }
  say "  [${node}] ${line}"
done
say "PASS (c): port 6000 listening on all five nodes"

# --- (d) clean stop via SIGTERM --------------------------------------------
say "check (d): SIGTERM stops all five servers (deadline ${STOP_DEADLINE}s)"
for node in "${SERVERS[@]}"; do
  on_node "${node}" "kill -TERM \$(cat ${PID_FILE})"
done
for node in "${SERVERS[@]}"; do
  deadline=$((SECONDS + STOP_DEADLINE))
  while on_node "${node}" "kill -0 \$(cat ${PID_FILE}) 2>/dev/null"; do
    if ((SECONDS >= deadline)); then
      dump_log_tail "${node}"
      fail "(d) ${node} still running ${STOP_DEADLINE}s after SIGTERM"
    fi
    sleep 1
  done
  say "  [${node}] $(on_node "${node}" \
    "grep -m1 'shutting down' ${LOG_FILE} || echo 'process gone'")"
done
say "PASS (d): all five servers stopped cleanly on SIGTERM"

say "ALL CHECKS PASSED"
