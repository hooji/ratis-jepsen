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
# Entry point for the containerized topology (DESIGN 3): control + n1..n7.
#
#   run.sh up     build image if needed, start compose, wait for ssh on all nodes
#   run.sh down   stop and remove containers, network and volumes; idempotent
#   run.sh test   stub until Job 04 lands the harness (exits 64)
#
# Environment knobs:
#   RJ_SSH_READY_TIMEOUT   seconds to wait for each node's sshd (default 120)
#   RJ_EXTRA_CA_BUNDLE     path to a PEM CA bundle to trust inside the image
#                          (TLS-inspecting proxies; see README.md)
#   RJ_DOCKER_BUILD_ARGS   extra args appended verbatim to `docker build`
set -euo pipefail

ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${ENV_DIR}/.state"
SSH_KEY="${STATE_DIR}/ssh/id_ed25519"
IMAGE="ratis-jepsen/env:latest"
NODES=(n1 n2 n3 n4 n5 n6 n7)
SSH_READY_TIMEOUT="${RJ_SSH_READY_TIMEOUT:-120}"

compose() {
  docker compose -f "${ENV_DIR}/docker-compose.yml" "$@"
}

# Requires the image to exist already (fallback path generates the key in a
# container, so hosts without ssh-keygen — some CI runners — still work).
ensure_ssh_key() {
  if [[ ! -f "${SSH_KEY}" || ! -f "${SSH_KEY}.pub" ]]; then
    mkdir -p "${STATE_DIR}/ssh"
    if command -v ssh-keygen >/dev/null 2>&1; then
      ssh-keygen -q -t ed25519 -N '' -C 'ratis-jepsen control->nodes' -f "${SSH_KEY}"
    else
      docker run --rm -v "${STATE_DIR}/ssh:/keys" "${IMAGE}" \
        ssh-keygen -q -t ed25519 -N '' -C 'ratis-jepsen control->nodes' \
        -f /keys/id_ed25519
    fi
    echo "run.sh: generated ssh keypair at ${SSH_KEY}[.pub]"
  fi
  chmod 600 "${SSH_KEY}"
}

build_image() {
  local args=(build --tag "${IMAGE}")
  if [[ -n "${RJ_EXTRA_CA_BUNDLE:-}" ]]; then
    # base64 < file | tr: portable across GNU and BSD/macOS base64
    args+=(--build-arg "EXTRA_CA_B64=$(base64 < "${RJ_EXTRA_CA_BUNDLE}" | tr -d '\n')")
  fi
  if [[ -n "${RJ_DOCKER_BUILD_ARGS:-}" ]]; then
    # shellcheck disable=SC2206  # intentional word splitting of user args
    args+=(${RJ_DOCKER_BUILD_ARGS})
  fi
  docker "${args[@]}" "${ENV_DIR}"
}

await_ssh_ready() {
  local node deadline
  for node in "${NODES[@]}"; do
    deadline=$((SECONDS + SSH_READY_TIMEOUT))
    until compose exec -T control ssh "root@${node}" true 2>/dev/null; do
      if ((SECONDS >= deadline)); then
        echo "run.sh: ERROR: node ${node} not ssh-ready from control" \
             "after ${SSH_READY_TIMEOUT}s" >&2
        echo "run.sh: try 'docker logs ratis-jepsen-${node}' and" \
             "'docker logs ratis-jepsen-control'" >&2
        exit 1
      fi
      sleep 1
    done
    echo "run.sh: ${node} ssh-ready"
  done
  echo "run.sh: all ${#NODES[@]} nodes ssh-ready"
}

cmd_up() {
  build_image
  ensure_ssh_key
  compose up -d --remove-orphans
  await_ssh_ready
}

cmd_down() {
  compose down --volumes --remove-orphans
}

# ---------------------------------------------------------------------------
# Job 04 replaces the body of cmd_test() with the real harness invocation
# (clojure -M:run test ... on the control node; DESIGN 3). Keep the
# subcommand plumbing; change only what is between the markers.
# ---------------------------------------------------------------------------
cmd_test() {
  # BEGIN Job-04 stub
  echo "ratis-jepsen: 'test' is a stub — the Jepsen harness arrives in Job 04." >&2
  echo "Use '$0 up' / '$0 down' for the cluster, env/validate.sh for the" \
       "environment proof." >&2
  exit 64
  # END Job-04 stub
}

usage() {
  sed -n 's/^#   run\.sh /  run.sh /p' "${BASH_SOURCE[0]}"
}

main() {
  case "${1:-}" in
    up)   cmd_up ;;
    down) cmd_down ;;
    test) shift; cmd_test "$@" ;;
    -h|--help|help|'') usage; [[ "${1:-}" ]] || exit 2 ;;
    *) echo "run.sh: unknown subcommand: $1" >&2; usage >&2; exit 2 ;;
  esac
}

main "$@"
