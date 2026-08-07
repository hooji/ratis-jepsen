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
# The metadata-durability probe (Job 11 deliverable 4, bounded, report-only).
#
# Question: Ratis persists term/votedFor in <storage>/<group>/current/raft-meta.
# Is that file DURABLE (synced to disk) by the time the node acts on the vote
# it records? A node whose vote outlives its memory but not its disk can vote
# twice in one term after a power loss — election safety gone.
#
# Source determination at ratis-3.2.2 (RaftServerImpl.requestVote,
# ServerState.initElection, RaftStorageMetadataFileImpl.atomicWrite,
# AtomicFileOutputStream + FileUtils.newOutputStreamForceAtClose): both vote
# paths call persistMetadata() synchronously BEFORE replying/acting, and the
# write path fsyncs (FileChannel.force(true)) the raft-meta.tmp before
# renaming it over raft-meta. The rename itself is NOT followed by a parent
# directory fsync — a real-power-loss edge OUTSIDE lazyfs's model (lazyfs
# passes renames straight to the backing store), documented in the Job 11
# report.
#
# This probe tests the part lazyfs CAN model, empirically, on the live
# durability topology:
#
#   per cycle:
#     1. census the leader; pick a follower VICTIM
#     2. start a tight sampler on the victim comparing raft-meta THROUGH the
#        lazyfs mount (what Ratis believes) against the BACKING copy (what
#        would survive power loss)
#     3. kill -9 the leader -> election -> the victim votes (term bump +
#        votedFor, persisted mid-election)
#     4. analyze samples: any moment where mount-term > backing-term is an
#        un-synced vote record (the un-durable window)
#     5. the power loss itself: kill -9 the victim, drop its un-synced lazyfs
#        cache, read the SURVIVING backing raft-meta, restart the victim and
#        compare its recovered term against the highest term it acted in
#     6. restart the killed leader; next cycle
#
#   PASS    (expected from source): no divergence ever sampled; the recovered
#           term never regresses below the acted term
#   FINDING (exit 2): divergence or regression — preserve everything printed
#           plus /tmp/metadata-probe.* on the nodes and triage before any
#           conclusion; this is the highest-severity class
#
# Preconditions:
#   env/run.sh up
#   env/run.sh test --durability --time-limit 30 --leave-db-running
#     (any short durability run left running: 5 voters, lazyfs mounts proven)
#
# Usage: harness/scripts/metadata-probe.sh [cycles]   # default 3

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DIR="$(cd "${SCRIPT_DIR}/../../env" && pwd)"
CYCLES="${1:-3}"
VOTERS=(n1 n2 n3 n4 n5)
STORAGE=/var/lib/ratis-kv
BACKING=/var/lib/ratis-kv.root
FIFO=/run/lazyfs-faults.fifo
PIDFILE=/run/ratis-kv.pid
LOG=/var/log/ratis-kv.log
SAMPLE_SECONDS=12
PEERS="n1=n1:6000,n2=n2:6000,n3=n3:6000,n4=n4:6000,n5=n5:6000"

compose() { docker compose -f "${ENV_DIR}/docker-compose.yml" "$@"; }
on()      { local node=$1; shift; compose exec -T control ssh "root@${node}" "$@"; }

finding=0

# --- helpers ---------------------------------------------------------------

# The node's current-leadership belief: last role-transition line ends in a
# transition TO LEADER (env/validate.sh check (b) convention).
is_leader() {
  local last
  last=$(on "$1" "grep 'changes role from' ${LOG} 2>/dev/null | tail -n 1" || true)
  [[ "${last}" == *"to LEADER at term"* ]]
}

current_leader() {
  local n
  for n in "${VOTERS[@]}"; do
    if is_leader "${n}"; then echo "${n}"; return 0; fi
  done
  return 1
}

await_leader() {
  local deadline=$((SECONDS + 60)) l
  while ((SECONDS < deadline)); do
    if l=$(current_leader); then echo "${l}"; return 0; fi
    sleep 1
  done
  echo "probe: ERROR: no leader within 60s" >&2
  exit 1
}

# raft-meta paths on a node (single fixed group => one storage subdir).
meta_path()   { on "$1" "ls -d ${STORAGE}/*/current/raft-meta 2>/dev/null | head -n1"; }

# "term=N votedFor=X" from a raft-meta file (Properties format), or "absent".
read_meta()   { on "$1" "grep -E '^(term|votedFor)=' '$2' 2>/dev/null | sort | tr '\n' ' ' || echo absent"; }

term_of()     { sed -n 's/.*term=\([0-9]*\).*/\1/p' <<<"$1"; }

start_sut() {
  # Mirrors harness db.clj start!* (cu/start-daemon! shape) so the probe's
  # restarts go through the same production path.
  on "$1" "mkdir -p ${STORAGE} && start-stop-daemon --start --background --no-close \
    --make-pidfile --pidfile ${PIDFILE} --chdir /opt/ratis-kv \
    --startas /opt/ratis-kv/bin/ratis-kv -- \
    --id $1 --peers ${PEERS} --storage ${STORAGE} >> ${LOG} 2>&1" || true
}

await_new_startup_line() {
  local node=$1 before=$2 deadline=$((SECONDS + 45)) count
  while ((SECONDS < deadline)); do
    count=$(on "${node}" "grep -c 'ratis-kv server started: ' ${LOG} 2>/dev/null || true")
    if [[ "${count:-0}" -gt "${before}" ]]; then return 0; fi
    sleep 1
  done
  echo "probe: WARNING: ${node} emitted no new startup line in 45s" >&2
  return 1
}

startup_lines() { on "$1" "grep -c 'ratis-kv server started: ' ${LOG} 2>/dev/null || true"; }

# --- probe cycles ----------------------------------------------------------

echo "probe: metadata-durability probe, ${CYCLES} cycle(s)"
echo "probe: voters=${VOTERS[*]} sample window=${SAMPLE_SECONDS}s @20ms"

for ((cycle = 1; cycle <= CYCLES; cycle++)); do
  echo
  echo "=== cycle ${cycle}/${CYCLES} ==="
  leader=$(await_leader)
  victim=""
  for n in "${VOTERS[@]}"; do
    if [[ "${n}" != "${leader}" ]]; then victim="${n}"; break; fi
  done
  echo "probe: leader=${leader} victim=${victim}"

  mnt_meta=$(meta_path "${victim}")
  if [[ -z "${mnt_meta}" ]]; then
    echo "probe: ERROR: no raft-meta on ${victim} under ${STORAGE}" >&2
    exit 1
  fi
  bak_meta="${mnt_meta/${STORAGE}/${BACKING}}"
  base_mnt=$(read_meta "${victim}" "${mnt_meta}")
  base_bak=$(read_meta "${victim}" "${bak_meta}")
  echo "probe: baseline mount  [${base_mnt}]"
  echo "probe: baseline backing[${base_bak}]"

  # Sampler on the victim: every ~20ms, one line comparing both copies.
  samples="/tmp/metadata-probe.${cycle}.samples"
  on "${victim}" "cat > /tmp/metadata-probe-sampler.sh" <<EOF
#!/bin/bash
end=\$(( \$(date +%s) + ${SAMPLE_SECONDS} ))
: > ${samples}
while [ \$(date +%s) -lt \${end} ]; do
  m=\$(grep -E '^(term|votedFor)=' '${mnt_meta}' 2>/dev/null | sort | tr '\n' ' ')
  b=\$(grep -E '^(term|votedFor)=' '${bak_meta}' 2>/dev/null | sort | tr '\n' ' ')
  echo "\$(date +%s.%N) MOUNT[\${m}] BACKING[\${b}]" >> ${samples}
  sleep 0.02
done
EOF
  on "${victim}" "nohup bash /tmp/metadata-probe-sampler.sh >/dev/null 2>&1 & echo sampler-started"

  # The election: power off the leader (kill -9), survivors re-elect; the
  # victim persists (term bump, vote) mid-window.
  leader_startups=$(startup_lines "${leader}")
  echo "probe: kill -9 leader ${leader}"
  on "${leader}" "kill -9 \$(cat ${PIDFILE}) 2>/dev/null || true"
  sleep 1
  new_leader=""
  deadline=$((SECONDS + 45))
  while ((SECONDS < deadline)); do
    for n in "${VOTERS[@]}"; do
      [[ "${n}" == "${leader}" ]] && continue
      if is_leader "${n}"; then new_leader="${n}"; break 2; fi
    done
    sleep 1
  done
  echo "probe: new leader after election: ${new_leader:-none-within-45s}"
  sleep 3   # let the sampler cover the post-election writes too

  # Analysis: any sample where mount-term > backing-term = un-synced vote
  # metadata (the un-durable window lazyfs would destroy).
  on "${victim}" "cat ${samples}" > "/tmp/metadata-probe-cycle${cycle}.samples" || true
  total=$(wc -l < "/tmp/metadata-probe-cycle${cycle}.samples")
  divergent=0
  while IFS= read -r line; do
    mt=$(sed -n 's/.*MOUNT\[[^]]*term=\([0-9]*\).*/\1/p' <<<"${line}")
    bt=$(sed -n 's/.*BACKING\[[^]]*term=\([0-9]*\).*/\1/p' <<<"${line}")
    if [[ -n "${mt}" && -n "${bt}" && "${mt}" -gt "${bt}" ]]; then
      divergent=$((divergent + 1))
      if ((divergent <= 3)); then echo "probe: DIVERGENT SAMPLE: ${line}"; fi
    fi
  done < "/tmp/metadata-probe-cycle${cycle}.samples"
  echo "probe: samples=${total} divergent(mount-term>backing-term)=${divergent}"
  if ((divergent > 0)); then finding=1; fi

  # The power loss on the victim: kill, drop un-synced cache, inspect what
  # SURVIVED in the backing store, restart, compare recovered vs acted term.
  acted=$(read_meta "${victim}" "${mnt_meta}")
  acted_term=$(term_of "${acted}")
  victim_startups=$(startup_lines "${victim}")
  echo "probe: victim acted state [${acted}] — killing + dropping un-synced cache"
  on "${victim}" "kill -9 \$(cat ${PIDFILE}) 2>/dev/null || true"
  on "${victim}" "timeout 5 sh -c 'echo lazyfs::clear-cache > ${FIFO}' && echo drop-acked" || echo "probe: WARNING: clear-cache send failed"
  survived=$(read_meta "${victim}" "${bak_meta}")
  survived_term=$(term_of "${survived}")
  echo "probe: survives power loss  [${survived}]"
  start_sut "${victim}"
  await_new_startup_line "${victim}" "${victim_startups}" || true
  recovered=$(read_meta "${victim}" "${mnt_meta}")
  recovered_term=$(term_of "${recovered}")
  echo "probe: recovered after loss [${recovered}]"
  if [[ -n "${acted_term}" && -n "${recovered_term}" ]] \
     && ((recovered_term < acted_term)); then
    echo "probe: *** TERM REGRESSION: acted at term ${acted_term}, recovered at ${recovered_term} — the node can re-vote in a term it already voted in. PRESERVE EVERYTHING. ***"
    finding=1
  else
    echo "probe: no regression (acted=${acted_term:-?} recovered=${recovered_term:-?})"
  fi

  # Heal: restart the killed leader for the next cycle.
  start_sut "${leader}"
  await_new_startup_line "${leader}" "${leader_startups}" || true
done

echo
if ((finding)); then
  echo "probe: VERDICT: FINDING — divergence and/or term regression observed."
  echo "probe: raw samples: /tmp/metadata-probe-cycle*.samples (host) and /tmp/metadata-probe.* on the nodes"
  exit 2
fi
echo "probe: VERDICT: PASS — raft-meta never diverged mount-vs-backing in any"
echo "probe: sample, and the recovered term never regressed: term/votedFor are"
echo "probe: durable by the time the node acts, within lazyfs's model (file"
echo "probe: data; renames pass through — see the report for the rename edge)."
