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
  # BEGIN Job-04 test body
  # Ensure the SUT tarball exists (build inside control if absent); the
  # repo is bind-mounted at /ratis-jepsen, so a host-built tarball counts.
  if ! compose exec -T control bash -c \
      "ls /ratis-jepsen/sut/ratis-kv/target/ratis-kv-*.tar.gz >/dev/null 2>&1"; then
    echo "run.sh: no SUT tarball; building inside control"
    compose exec -T control /ratis-jepsen/sut/ratis-kv/mvnw \
      -f /ratis-jepsen/sut/ratis-kv/pom.xml -q package
  fi
  # jepsen's run! shells out to `git` for provenance logging and dies if
  # the binary is missing outright (it handles nonzero exits fine); the
  # image ships no git, so give control a benign always-fails stand-in.
  # No-op once the image grows real git.
  compose exec -T control bash -c \
    'command -v git >/dev/null 2>&1 \
       || { printf "#!/bin/sh\nexit 1\n" > /usr/local/bin/git \
            && chmod +x /usr/local/bin/git; }'
  # jepsen's iptables net wraps node commands in `sudo -k -S -u root
  # bash -c ...`; the image has no sudo and ssh lands as root already, so
  # give each node an exec stand-in that swallows sudo's flags and runs
  # the command. Stdin is deliberately left untouched: jepsen's su path
  # sends no password, and a blocking read would deadlock every wrapped
  # command (sshj holds the channel's stdin open). Skipped where real
  # sudo exists (/usr/bin), so it's a no-op once the image grows sudo.
  local node
  for node in "${NODES[@]}"; do
    compose exec -T "${node}" bash -c '[ -x /usr/bin/sudo ] || {
      cat > /usr/local/bin/sudo <<"SHIM"
#!/bin/bash
while [ $# -gt 0 ]; do
  case "$1" in
    -u) shift 2 ;;
    --) shift; break ;;
    -*) shift ;;
    *)  break ;;
  esac
done
exec "$@"
SHIM
      chmod +x /usr/local/bin/sudo; }'
  done
  # Run the harness on control against the five voters. store/ lands on
  # the bind mount (/ratis-jepsen/store — gitignored) so results survive
  # `down`. Remaining args pass through to the harness CLI (--nemesis,
  # --time-limit, --seed-bug, ...). The harness exit code is the test
  # verdict (0 = checker valid) and, via `set -e`, becomes ours.
  compose exec -T control bash -c \
    'cd /ratis-jepsen/harness && exec clojure -M:run test \
       --store-dir /ratis-jepsen/store \
       --nodes n1,n2,n3,n4,n5 \
       --ssh-private-key /root/.ssh/id_ed25519 \
       "$@"' harness-test "$@"
  # END Job-04 test body
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
