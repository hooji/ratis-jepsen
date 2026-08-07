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
#   run.sh test   run the harness on control; args pass through (--nemesis, --time-limit, --seed-bug, ...) and the harness exit code is the verdict
#
# Environment knobs:
#   RJ_SSH_READY_TIMEOUT   seconds to wait for each node's sshd (default 120)
#   RJ_EXTRA_CA_BUNDLE     path to a PEM CA bundle to trust inside the image
#                          (TLS-inspecting proxies; see README.md)
#   RJ_DOCKER_BUILD_ARGS   extra args appended verbatim to `docker build`
#   RJ_RATIS_REPO_URL      extra Maven repository for Ratis artifacts not on
#                          Central (e.g. an Apache staging repo while a
#                          release candidate is under vote): passed to the
#                          SUT build as -Dratis.repo.url and injected into
#                          the harness's dependency resolution (Job 12)
set -euo pipefail

ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${ENV_DIR}/.state"
SSH_KEY="${STATE_DIR}/ssh/id_ed25519"
IMAGE="ratis-jepsen/env:latest"
NODES=(n1 n2 n3 n4 n5 n6 n7)
SSH_READY_TIMEOUT="${RJ_SSH_READY_TIMEOUT:-120}"
# The CA bundle travels base64-encoded as ONE docker build-arg, i.e. one
# exec(2) argument, and the kernel caps a single argument at ~128 KiB
# (MAX_ARG_STRLEN); base64 inflates by 4/3. 64 KiB of PEM stays safely
# under that and is ~30 certificates — far more than the "just your
# proxy's CA(s)" the knob is for.
CA_BUNDLE_MAX_BYTES=65536

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

# Catch bad RJ_EXTRA_CA_BUNDLE input here, with instructions, instead of
# letting docker build die later with a cryptic error (an oversized bundle
# hits the kernel's per-argument cap as 'Argument list too long').
preflight_ca_bundle() {
  local bundle=$1 size
  if [[ ! -r "${bundle}" ]]; then
    echo "run.sh: ERROR: RJ_EXTRA_CA_BUNDLE=${bundle} is not a readable file" >&2
    exit 1
  fi
  if ! grep -q -- '-----BEGIN CERTIFICATE-----' "${bundle}"; then
    echo "run.sh: ERROR: RJ_EXTRA_CA_BUNDLE=${bundle} contains no PEM" \
         "certificate (no '-----BEGIN CERTIFICATE-----' line; DER input?)." \
         "Convert with: openssl x509 -inform der -in <file> -out <file>.pem" >&2
    exit 1
  fi
  size=$(wc -c < "${bundle}")
  if ((size > CA_BUNDLE_MAX_BYTES)); then
    echo "run.sh: ERROR: RJ_EXTRA_CA_BUNDLE=${bundle} is ${size} bytes," \
         "over the ${CA_BUNDLE_MAX_BYTES}-byte cap. It travels as a single" \
         "docker build-arg, so base64-encoded it would exceed the kernel's" \
         "~128 KiB per-argument limit and docker build would fail with" \
         "'Argument list too long'. Pass only the CA certificate(s) your" \
         "proxy chain actually needs, not a full system bundle — see the" \
         "RJ_EXTRA_CA_BUNDLE notes in env/README.md." >&2
    exit 1
  fi
}

build_image() {
  local args=(build --tag "${IMAGE}")
  if [[ -n "${RJ_EXTRA_CA_BUNDLE:-}" ]]; then
    preflight_ca_bundle "${RJ_EXTRA_CA_BUNDLE}"
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
# The Ratis version(s) a `test` invocation runs against (Job 12, M5):
# --ratis-version V selects the SUT tarball AND the harness's own
# ratis-client deps; --mixed-version OLD,NEW needs both versions' tarballs
# and puts the harness client on OLD (clients upgrade last). Versions are
# validated here because they are spliced into shell and edn strings.
RATIS_VERSION_DEFAULT="3.2.2"
VERSION_TOKEN_RE='^[0-9A-Za-z._-]+$'

# Scans pass-through harness args for --ratis-version/--mixed-version
# (both stay in the pass-through — the harness needs them too) and sets
# TEST_VERSIONS (space-separated, tarballs to ensure) and CLIENT_VERSION
# (the harness JVM's ratis-client).
parse_test_versions() {
  local ratis_version="" mixed="" i args=("$@")
  for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[i]}" in
      --ratis-version) ratis_version="${args[i + 1]:-}" ;;
      --mixed-version) mixed="${args[i + 1]:-}" ;;
    esac
  done
  ratis_version="${ratis_version:-${RATIS_VERSION_DEFAULT}}"
  if [[ ! "${ratis_version}" =~ ${VERSION_TOKEN_RE} ]]; then
    echo "run.sh: ERROR: --ratis-version '${ratis_version}' is not a plain version token" >&2
    exit 2
  fi
  if [[ -n "${mixed}" ]]; then
    local old="${mixed%%,*}" new="${mixed#*,}"
    if [[ "${mixed}" != *,* || ! "${old}" =~ ${VERSION_TOKEN_RE} \
          || ! "${new}" =~ ${VERSION_TOKEN_RE} || "${old}" == "${new}" ]]; then
      echo "run.sh: ERROR: --mixed-version '${mixed}' must be OLD,NEW (two distinct version tokens)" >&2
      exit 2
    fi
    TEST_VERSIONS="${old} ${new}"
    CLIENT_VERSION="${old}"
  else
    TEST_VERSIONS="${ratis_version}"
    CLIENT_VERSION="${ratis_version}"
  fi
}

# Ensures the SUT tarball for one Ratis version exists (build inside
# control if absent); the repo is bind-mounted at /ratis-jepsen, so a
# host-built tarball counts.
ensure_tarball() {
  local version=$1
  if ! compose exec -T control bash -c \
      "ls /ratis-jepsen/sut/ratis-kv/target/ratis-kv-*-ratis-${version}.tar.gz >/dev/null 2>&1"; then
    echo "run.sh: no SUT tarball for ratis ${version}; building inside control"
    compose exec -T control /ratis-jepsen/sut/ratis-kv/mvnw \
      -f /ratis-jepsen/sut/ratis-kv/pom.xml -q package \
      "-Dratis.version=${version}" \
      ${RJ_RATIS_REPO_URL:+"-Dratis.repo.url=${RJ_RATIS_REPO_URL}"}
  fi
}

cmd_test() {
  # BEGIN Job-04 test body
  parse_test_versions "$@"
  local version
  for version in ${TEST_VERSIONS}; do
    ensure_tarball "${version}"
  done
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
  #
  # Version matrix (Job 12): the harness JVM's own ratis-client/-grpc/
  # -metrics-default must match the server under test (the mixed pair's
  # OLD half on mixed runs — clients upgrade last), so the deps are
  # overridden at launch via -Sdeps :override-deps from CLIENT_VERSION;
  # core.clj re-checks the real classpath and refuses on skew. With
  # RJ_RATIS_REPO_URL set, the same -Sdeps adds the extra repository so
  # staged (RC) artifacts resolve inside control.
  local sdeps="{"
  if [[ -n "${RJ_RATIS_REPO_URL:-}" ]]; then
    if [[ ! "${RJ_RATIS_REPO_URL}" =~ ^https?://[^[:space:]\"\{\}]+$ ]]; then
      echo "run.sh: ERROR: RJ_RATIS_REPO_URL '${RJ_RATIS_REPO_URL}' is not a plain http(s) URL" >&2
      exit 2
    fi
    sdeps+=":mvn/repos {\"extra-ratis-repo\" {:url \"${RJ_RATIS_REPO_URL}\"}} "
  fi
  sdeps+=":aliases {:sut-ratis {:override-deps {"
  sdeps+="org.apache.ratis/ratis-client {:mvn/version \"${CLIENT_VERSION}\"} "
  sdeps+="org.apache.ratis/ratis-grpc {:mvn/version \"${CLIENT_VERSION}\"} "
  sdeps+="org.apache.ratis/ratis-metrics-default {:mvn/version \"${CLIENT_VERSION}\"}}}}}"
  compose exec -T control bash -c \
    'cd /ratis-jepsen/harness && exec clojure -Sdeps "$1" -M:run:sut-ratis test \
       --store-dir /ratis-jepsen/store \
       --nodes n1,n2,n3,n4,n5 \
       --ssh-private-key /root/.ssh/id_ed25519 \
       "${@:2}"' harness-test "${sdeps}" "$@"
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
