# Job 02 report — `env/`: containerized cluster topology and entry point

## Summary

`env/` now exists: one multi-arch-by-construction Docker image (Ubuntu
24.04, OpenJDK 21, sshd with root key login, fault-injection tools, Clojure
CLI 1.12.1.1550), a compose topology of `control` plus `n1..n7` on a
private bridge network with the repo mounted at `/ratis-jepsen` on control,
per-checkout ssh-key wiring under gitignored `env/.state/`, `run.sh`
(`up`/`down`/`test`-stub), and `validate.sh`, which proves the DESIGN §2.6
deployment contract end to end: SUT tarball built inside control, installed
and started on `n1..n5`, startup line on all five, exactly one leader
elected, port 6000 listening everywhere, clean SIGTERM stop. All eight
acceptance criteria pass. The two decisions to review hardest: (1) the
image carries an optional `EXTRA_CA_B64` build-arg (no-op when unset) that
trusts an operator-supplied proxy CA in both the system and JVM stores —
without it neither the Clojure install at build time nor Maven inside
control works on TLS-inspected hosts like this sandbox; (2) `run.sh up`
builds the image *before* ensuring keys so key generation can fall back to
a container when the host lacks `ssh-keygen` (this sandbox does).

## What was built

| File | One line |
|---|---|
| `env/Dockerfile` | single image for all roles: Ubuntu 24.04 + apt OpenJDK 21 + sshd (root key-only) + iproute2/iptables/psmisc/procps/rsync/curl + Clojure CLI via the official arch-neutral installer; optional `EXTRA_CA_B64` trust block; `CMD` recreates `/run/sshd` then execs `sshd -D -e` |
| `env/docker-compose.yml` | project `ratis-jepsen`: `control` (repo at `/ratis-jepsen`, private key, `maven-repo` volume) + `n1..n7` (privileged, public key as read-only `authorized_keys`), one bridge network, service names = hostnames |
| `env/ssh_config` | baked client config: no host-key prompts for `n1..n7` inside the throwaway network |
| `env/.dockerignore` | keeps `.state/` (keys!) and README out of the build context |
| `env/run.sh` | `up` (build → ensure keys → compose up → per-node ssh readiness with named-node failure), `down` (`--volumes --remove-orphans`, idempotent), `test` (Job 04 stub, exit 64, marked replace-me body); knobs `RJ_SSH_READY_TIMEOUT`, `RJ_EXTRA_CA_BUNDLE`, `RJ_DOCKER_BUILD_ARGS` |
| `env/validate.sh` | brief deliverable 5: up → in-control `mvnw -q package` → install to `/opt/ratis-kv` on `n1..n5` → start per contract → checks (a)–(d) with per-check evidence lines, log-tail dumps on failure, exit 0 only if all hold |
| `env/README.md` | usage, knobs, network requirements, §2.6 contract table (copied, DESIGN cited as source of truth), arch support status |
| `.gitignore` | one appended block: `env/.state/` |

## How it was verified

Environment: Linux x86_64, Docker Engine 29.3.1 / Compose v5.1.1. This
sandbox TLS-inspects all container egress, so runs pass
`RJ_EXTRA_CA_BUNDLE=<proxy CA pem>`; on a normal dev machine the variable
is unnecessary (see Deviations for how the daemon itself was started).

**Criterion 1 — `up` from a clean checkout reaches ssh-ready.**

```
$ RJ_EXTRA_CA_BUNDLE=.../proxy-ca.pem env/run.sh up
...
 Container ratis-jepsen-n7 Started
run.sh: n1 ssh-ready
run.sh: n2 ssh-ready
run.sh: n3 ssh-ready
run.sh: n4 ssh-ready
run.sh: n5 ssh-ready
run.sh: n6 ssh-ready
run.sh: n7 ssh-ready
run.sh: all 7 nodes ssh-ready
```

**Criterion 2 — non-interactive root ssh from control.**

```
$ docker compose -f env/docker-compose.yml exec -T control ssh root@n3 true ; echo n3=$?
n3=0
$ docker compose -f env/docker-compose.yml exec -T control ssh root@n7 true ; echo n7=$?
n7=0
```

**Criterion 3 — `validate.sh` exits 0** (background task, full log kept;
exit code 0). The in-control build ran the entire SUT test suite (`-q
package`, no skip) — the log shows the in-JVM smoke test's three-node
cluster running inside the control container — then:

- (a) startup line on all five nodes (n1 shown; all five printed):

  ```
  validate:   [n1] 2026-08-04 14:02:36.997 [main] INFO ratis.jepsen.kv.Main - ratis-kv server started: id=n1 address=n1:6000 storage=/var/lib/ratis-kv group=group-ABBC16E54704 peers={n1=n1:6000, n2=n2:6000, n3=n3:6000, n4=n4:6000, n5=n5:6000}
  validate: PASS (a): startup line present on all five nodes
  ```

- (b) exactly one leader; the observed Ratis line, quoted verbatim:

  ```
  validate:   [n1] 2026-08-04 14:02:39.882 [n1@group-ABBC16E54704-LeaderElection1] INFO org.apache.ratis.server.RaftServer$Division - n1@group-ABBC16E54704: changes role from CANDIDATE to LEADER at term 1 for changeToLeader
  validate: PASS (b): exactly one node (n1) became LEADER
  ```

  (`validate.sh` greps for `changes role from .* to LEADER`.)

- (c) port 6000 on all five (n1 shown):

  ```
  validate:   [n1] LISTEN 0      4096         0.0.0.0:6000       0.0.0.0:*
  validate: PASS (c): port 6000 listening on all five nodes
  ```

- (d) clean SIGTERM stop, evidenced by the SUT's shutdown-hook line and
  process exit on every node (n1 shown):

  ```
  validate:   [n1] 2026-08-04 14:02:54.607 [ratis-kv-shutdown] INFO ratis.jepsen.kv.Main - ratis-kv server n1 shutting down
  validate: PASS (d): all five servers stopped cleanly on SIGTERM
  validate: ALL CHECKS PASSED
  ```

**Criterion 4 — `down` cleans up; second `down` no-op.**

```
$ env/run.sh down
 ...
 Volume ratis-jepsen_maven-repo Removed
 Network ratis-jepsen_jepsen Removed
$ docker ps -a --filter name=ratis-jepsen- -q | wc -l
0
$ env/run.sh down ; echo EXIT=$?
EXIT=0
```

**Criterion 5 — `up` after `down` (no state leakage).**

```
$ RJ_EXTRA_CA_BUNDLE=... env/run.sh up
...
run.sh: all 7 nodes ssh-ready
```

(Named volume and network are removed by `down`, so the cycle starts
clean; the generated ssh keypair under `env/.state/` deliberately persists
— it is configuration, regenerated if deleted.)

**Criterion 6 — arch neutrality.** The image built on x86_64 (criterion 1
run). Arch selection is entirely delegated: `ubuntu:24.04` is a multi-arch
manifest, every package (OpenJDK 21 included) comes from apt, and the
Clojure CLI is the official pure-JVM installer script.

```
$ grep -nEi "amd64|x86_64|arm64|aarch64" env/Dockerfile || echo "no arch-pinned strings in Dockerfile"
no arch-pinned strings in Dockerfile
```

**Criterion 7 — hygiene.** Apache-2.0 headers (shell-comment form) on
`Dockerfile`, `docker-compose.yml`, `ssh_config`, `run.sh`, `validate.sh`.
`git check-ignore env/.state/ssh/id_ed25519` confirms key material is
ignored; `git status` shows only `env/**` files and the `.gitignore`
append. (`env/.dockerignore` is a two-comment mechanical exclusion list and
carries no header; flagging for the reviewer rather than silently deciding
it is exempt.)

**Criterion 8** — this file.

## Deviations from the brief

- **`docker compose` direct use is unsupported without `run.sh`.** The
  compose file mounts `env/.state/ssh/*`, which `run.sh up` creates.
  Running bare `docker compose up` on a fresh checkout would let Docker
  create directories at those paths. Documented in README ("always drive
  through run.sh"); accepted for M0.
- **Optional CA-trust build-arg added to the Dockerfile.** Not in the
  brief's parts list. This sandbox (and corporate/CI hosts generally)
  TLS-intercepts container egress; without trusting the proxy CA, the
  build's Clojure download and every in-control Maven/Clojure download
  fail TLS verification. Implemented as the standard conditional pattern —
  `ARG EXTRA_CA_B64` decoded into `/usr/local/share/ca-certificates/` +
  `update-ca-certificates` (which also refreshes the JVM keystore via the
  ca-certificates-java hook) — a strict no-op when unset, wired to
  `RJ_EXTRA_CA_BUNDLE` in `run.sh`. On unproxied hosts nothing changes.
- **Key generation falls back to a container.** This sandbox host has no
  `ssh-keygen`, so `up` builds the image first and generates the keypair
  inside it when the host tool is missing. Host `ssh-keygen` is preferred
  when present. (This reordering is why build failures surface before key
  generation — harmless either way.)
- **Sandbox precondition, not a repo change:** the Docker daemon was not
  running in this Claude Code Cloud container; I started `dockerd`
  manually before the runs. On dev machines/CI with a running daemon this
  does not apply. Also, the *first* `validate.sh` run downloads Maven and
  all dependencies inside control (~2 GB image + ~250 MB of artifacts
  total); subsequent runs within an up-cycle reuse the `maven-repo`
  volume.

## Known gaps and risks

- **arm64 is untested.** No arm64 machine was available to this job. The
  Dockerfile is arch-neutral by construction (apt + multi-arch base +
  pure-JVM Clojure), and README states support as "expected, untested".
  First Apple-silicon `run.sh up` should be treated as the real test.
- **Leader-uniqueness check can flake by design.** Criterion 5b counts
  nodes whose log ever shows a LEADER transition; a split-vote first
  election (possible with 1–2 s randomized timeouts, seen zero times in
  these runs) would produce two such nodes and fail `validate.sh`, which
  can simply be re-run. The stricter alternative (last-role-wins per node)
  was left out to keep the check exactly what the brief asked for.
- **`validate.sh` leaves the cluster up** (servers stopped, containers
  running) for inspection; `run.sh down` is the cleanup. Deliberate, but
  worth knowing in CI contexts.
- **Root-owned files in the repo mount.** Builds inside control write
  `sut/ratis-kv/target/` as root into the bind-mounted repo. Both are
  gitignored; on dev machines this may need an occasional `sudo rm`.
- **sshd runs in control too** (single image, single CMD). Nothing SSHes
  *to* control; its sshd never has an authorized key mounted, so root
  login there is impossible (`prohibit-password` + no keys). Noted so
  nobody mistakes it for an oversight.
- The compose file sets `privileged: true` on `n1..n7` only; `control`
  runs unprivileged (it injects no faults on itself). If Job 04's harness
  ever needs privileged operations on control, that is a one-line change.

## Suggestions (out of scope)

- Job 03/04's `db.clj` should reuse `validate.sh`'s signals verbatim:
  await `ratis-kv server started:` in `/var/log/ratis-kv.log`, and treat
  `changes role from .* to LEADER` (observed phrasing:
  `changes role from CANDIDATE to LEADER at term 1 for changeToLeader`)
  as the leader-election marker if it ever needs one.
- A `run.sh shell [node]` convenience (exec bash in control/node) would
  save contributors repeated `docker compose exec` incantations.
- When the repo goes public and CI lands (M1), cache both the image build
  (registry or GHA cache) and the `maven-repo` volume equivalent — the
  cold path is several minutes; warm is seconds.
- Consider pre-warming the Clojure deps cache in the image once Job 03's
  `deps.edn` exists (DESIGN 3 mentions a warmed cache for control).

## Revision 1 (2026-08-04, per `jobs/02-env/02_revision_1.md`)

**Item 1 of 1 — `validate.sh` check (b) now asserts *current* leadership,
not election history.** Changes, all in `env/validate.sh` (plus one usage
line in `env/README.md` and the script's header comment):

- `count_leaders` now takes, per node, only the **last**
  `changes role from` line in the log
  (`grep 'changes role from' ${LOG_FILE} | tail -n 1`, via the new
  `last_transition` helper) and counts the node iff that line contains
  `to LEADER`. A superseded leader's later `LEADER to FOLLOWER` line
  removes it from the census.
- After the initial any-leader wait and the (kept) settle sleep, the
  census is **re-sampled once per second until it reads exactly 1**,
  bounded by the same `LEADER_DEADLINE` computed at the start of the
  check; a mid-handover sample of 0 or 2 is not a failure. Only hitting
  the deadline fails, and the failure path now prints every node's last
  role-transition line as evidence.
- The winning node's `to LEADER` line is still printed verbatim (now via
  `... | tail -n 1`, so a re-elected cluster quotes the *current*
  leadership line).

**Verification — quiet baseline** (semantics unchanged for a calm
cluster): full `validate.sh` run, exit 0:

```
validate: check (b): exactly one current leader (deadline 90s)
validate:   [n3] ... n3@group-ABBC16E54704: changes role from CANDIDATE to LEADER at term 1 for changeToLeader
validate: PASS (b): exactly one node (n3) is currently LEADER
...
validate: ALL CHECKS PASSED
```

**Verification — the reviewer's stall scenario** (`RJ_LEADER_SETTLE=25`;
an orchestration script watched the validate output, found the current
leader after `PASS (a)`, and ran
`ssh root@<leader> 'kill -STOP $(cat /var/run/ratis-kv.pid); sleep 4; kill -CONT $(cat /var/run/ratis-kv.pid)'`
during the settle window):

```
stall: pausing leader n1 for 4s (SIGSTOP/SIGCONT)
stall: n1 resumed
...
validate: check (b): exactly one current leader (deadline 90s)
validate:   [n5] 2026-08-04 15:35:05.621 [n5@group-ABBC16E54704-LeaderElection1] INFO org.apache.ratis.server.RaftServer$Division - n5@group-ABBC16E54704: changes role from CANDIDATE to LEADER at term 2 for changeToLeader
validate: PASS (b): exactly one node (n5) is currently LEADER
...
validate: ALL CHECKS PASSED     (exit 0)
```

The logs confirm the scenario really exercised the new semantics — two
`to LEADER` lines existed cluster-wide (the old check would have counted
2 and failed a healthy cluster), and the paused leader's last line is a
step-down:

```
[n1] changes role from CANDIDATE to LEADER at term 1 for changeToLeader
[n1] changes role from    LEADER to FOLLOWER at term 1 for StepDownReason:LOST_MAJORITY_HEARTBEATS
[n5] changes role from CANDIDATE to LEADER at term 2 for changeToLeader   (last line; the one counted)
```

**Known-gaps update.** The original entry "leader-uniqueness check can
flake by design" is superseded by this revision: an early re-election no
longer fails the run. The residual (much smaller) caveat is that a
cluster still churning leaders at the *deadline* fails — which at that
point is signal, not flake. One measurement artifact worth recording for
whoever automates around `validate.sh`: while a paused process holds the
pid, `kill -0` keeps succeeding, so a SIGSTOP that overlaps check (d)
would stall the stop-wait until `kill -CONT` — not reachable in the
committed flow (no pausing there), only in harnesses like the revision's
own stall orchestration.
