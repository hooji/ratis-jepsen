# env/ — containerized cluster topology

One Docker image for every role, a compose topology of `control` plus db
nodes `n1..n7` on a private bridge network (Jepsen convention: control
SSHes to nodes as root), and a single entry script. Built by Job 02; the
Clojure harness (Jobs 03/04) runs on `control` and drives the nodes over
SSH.

## Usage

```
env/run.sh up        # build image (cached), start compose, wait for ssh on all 7 nodes
env/run.sh down      # stop and remove containers, network, volumes; idempotent
env/run.sh test      # run the harness on control; args pass through
                     # (--nemesis, --time-limit, --seed-bug, ...) and the
                     # harness exit code is the verdict (0 = checker valid)
env/validate.sh      # end-to-end proof: SUT build on control, 5-node boot,
                     # exactly one *current* leader (last role transition
                     # per node), ports, clean SIGTERM stop
```

Always drive the topology through `run.sh` (not bare `docker compose`):
`up` first generates the per-checkout ssh keypair under `env/.state/`
(gitignored, never committed) that the compose volume mounts rely on.
Nodes trust the public key via a read-only `authorized_keys` mount;
`control` gets the private key; host-key checking is disabled inside the
throwaway compose network (`env/ssh_config`).

Knobs (environment variables):

| Variable | Meaning |
|---|---|
| `RJ_SSH_READY_TIMEOUT` | seconds to wait per node for sshd (default 120) |
| `RJ_EXTRA_CA_BUNDLE` | path to a PEM file with the proxy's CA certificate(s), baked into the image's system **and** JVM trust stores — needed on hosts whose egress is TLS-inspected (corporate/CI proxies). Multi-cert bundles are fully supported: the Dockerfile splits the bundle one-cert-per-file, because Debian's JVM-keystore hook imports only the first cert of a file. Still pass just the extra CA(s), not a full system bundle: the content travels as a single docker build-arg, so `run.sh` pre-flights the file (readable, contains a PEM cert, ≤ 64 KiB) and refuses oversized input before docker can fail with a cryptic `Argument list too long` |
| `RJ_DOCKER_BUILD_ARGS` | extra arguments appended verbatim to `docker build` |
| `RJ_STARTUP_DEADLINE`, `RJ_LEADER_DEADLINE`, `RJ_LEADER_SETTLE`, `RJ_STOP_DEADLINE` | validate.sh deadlines (seconds) |

Network note: the image build (apt + Clojure CLI download) and the first
`validate.sh` run (Maven Wrapper + dependencies inside `control`, cached in
a named volume for the rest of the up-cycle) need outbound network access.
Behind a TLS-inspecting proxy, point `RJ_EXTRA_CA_BUNDLE` at the proxy's CA
bundle before `run.sh up`.

`maven-repo` volume lifecycle: the named volume mounted at `/root/.m2` on
`control` persists Maven and Clojure dependency downloads across container
recreations *within* one up-cycle — that is why the second `validate.sh`
or `test` run in a cycle skips the big downloads and is much faster —
while `run.sh down` removes it (`down --volumes`), so successive up-cycles
share no state and stay hermetic: fast within a cycle, cold across cycles
— by design, not by accident.

The repository is bind-mounted read-write at `/ratis-jepsen` on `control`
only. Builds run inside `control` as root, so `sut/ratis-kv/target/` on the
host may end up root-owned — both are gitignored.

## Deployment contract

Copied from `docs/DESIGN.md` §2.6, which is the source of truth (pinned
2026-08-04); `env/` and the harness both build to it. If they ever
disagree, DESIGN wins.

| Item | Value |
|---|---|
| Nodes | `n1..n7` (`n1..n5` initial voters; `n6`/`n7` dormant pool), user `root`, passwordless ssh from `control` |
| Raft port | `6000` on every node |
| Install dir | `/opt/ratis-kv` (tarball unpacked: `/opt/ratis-kv/bin/ratis-kv`, `/opt/ratis-kv/lib/`) |
| Storage dir | `/var/lib/ratis-kv` |
| Log | stdout captured to `/var/log/ratis-kv.log` |
| Startup line | after `RaftServer.start()`, stdout emits `ratis-kv server started: id=<id> address=<host:port> storage=<dir> group=<uuid> peers=<list>` — the boot-await signal for env validation and `db.clj` (confirmed present at Job 01's merge; changing it is a breaking change requiring a brief) |

Compose service names equal hostnames `n1..n7` exactly (the harness's node
list uses those literal names). `n6`/`n7` are up but run no SUT in M0.

## lazyfs (durability faults, Job 11 / M4)

The image carries a [lazyfs](https://github.com/dsrhaslab/lazyfs) binary at
`/opt/lazyfs/lazyfs`, built in a throwaway Docker stage at the Job 10
spike's pinned commit (`045a0b3a1126725e693934e29d3ba15e08cc39ec`; the
project's main branch is explicitly unstable, so the pin is load-bearing —
re-verify the build before bumping it). The build pre-clones spdlog
`v1.10.0` and feeds it to libpcache's CMake via
`FETCHCONTENT_SOURCE_DIR_SPDLOG` instead of letting FetchContent pull a
GitHub archive tarball — the spike's accommodation for TLS-inspecting
proxies that 403 archive downloads; on open networks it changes nothing.
The runtime image adds only `libfuse3-3` + `fuse3` and `user_allow_other`
in `/etc/fuse.conf`.

All of it is **inert unless a harness run passes `--durability`** (or a
durability nemesis, which forces it): no mount exists, no lazyfs process
runs, and every other scenario behaves exactly as before. With
`--durability`, the harness mounts each node's `/var/lib/ratis-kv` as a
lazyfs FUSE mount over the backing dir `/var/lib/ratis-kv.root`, proving
the mount per node and failing the run loudly if it cannot. FUSE mounts
need `/dev/fuse`, which the privileged db-node containers get from the
host kernel.

## Architecture support

Nothing in the runtime image is arch-pinned: the base `ubuntu:24.04` is a
multi-arch manifest, all packages (OpenJDK 21 included) come from apt,
which resolves per-architecture, and the Clojure CLI is pure-JVM installed
by the official arch-neutral installer script. The one exception is the
lazyfs build stage, which runs on amd64 only (PLAN Q8: lazyfs is
x86-CI-only for now): on other arches `/opt/lazyfs` is empty, every
non-durability scenario is unaffected, and a `--durability` run fails
loudly at mount proof.

- **x86_64**: built and fully validated (image build, 8-node topology,
  `validate.sh` green) on Linux x86_64.
- **arm64**: expected to build unmodified for the reasons above, but not
  yet exercised — no arm64 machine was available to this job. Treat as
  untested until a dev-machine run confirms it.
