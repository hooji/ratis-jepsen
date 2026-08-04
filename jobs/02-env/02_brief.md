# Job 02 — `env/`: containerized cluster topology and entry point

*Coordinator brief, 2026-08-04.*

**Before anything else, read `jobs/README.md` — it is binding.** Then
`docs/PLAN.md`, `docs/DESIGN.md` (§2.6 Deployment contract and §3 are
this job's spec), and this brief. If you completed a previous job in
this session: fetch origin and base your branch on **current** `main`
(Job 01 is merged there; the deployment contract in DESIGN §2.6 is new).

## Context

Job 01 (merged) built `sut/ratis-kv`: `sut/ratis-kv/mvnw -f
sut/ratis-kv/pom.xml package` produces a tarball with `bin/ratis-kv`
and `lib/*.jar`; the launcher contract is in `jobs/01-sut/01_brief.md`.
This job builds the place that server runs: a Docker topology of one
`control` node and seven db nodes, in the Jepsen convention (control
SSHes to nodes as root), with a single entry script. The Clojure
harness (jobs 03/04) will run **on control** and drive nodes over SSH,
relying exactly on DESIGN §2.6's deployment contract — treat that table
as frozen.

## Deliverables

1. **`env/Dockerfile`** — one image for all roles: Debian(-slim) or
   Ubuntu base, OpenJDK **21** (Temurin or distro), `sshd` configured
   for root key login, `iproute2`, `iptables`, `psmisc`, `procps`,
   `rsync`/`scp`, and the **Clojure CLI tools** (used by the harness on
   control in Job 04 — installing now avoids an image change later).
   Must build unmodified on both arm64 and x86_64 (multi-arch base
   images, no arch-pinned downloads: select JDK/Clojure artifacts by
   `dpkg --print-architecture` or use arch-neutral installers).
2. **`env/docker-compose.yml`** — services `control`, `n1`..`n7`; one
   bridge network with service-name hostnames; `privileged: true` on
   all nodes (iptables/SIGSTOP now, FUSE experiments later); the
   repository bind-mounted read-write at **`/ratis-jepsen`** on
   `control` only.
3. **SSH wiring** — `control` can `ssh root@nX` non-interactively for
   all seven nodes. Suggested shape (yours to finalize): `run.sh up`
   generates a keypair under `env/.state/` (gitignored — extend
   `.gitignore`, that path only), mounted/injected so nodes trust it;
   host-key checking disabled or pre-seeded inside the compose network.
   No secrets committed, ever.
4. **`env/run.sh`** — subcommands:
   - `up`: build image (if needed), start compose, block until sshd on
     all nodes answers from control; loud failure with the offending
     node named.
   - `down`: stop and remove containers, network, and anonymous
     volumes; idempotent.
   - `test`: documented stub for Job 04 — prints that the harness
     arrives in Job 04 and exits with code 64. Structure it so Job 04
     replaces only the stub body.
5. **`env/validate.sh`** — the end-to-end proof (run from the repo root
   on the host): `run.sh up`, then **inside control**: build the SUT
   tarball (`/ratis-jepsen/sut/ratis-kv/mvnw -f
   /ratis-jepsen/sut/ratis-kv/pom.xml -q package`), copy it to
   `n1`..`n5`, unpack at `/opt/ratis-kv`, start each server per the
   deployment contract (`--id nX --peers
   n1=n1:6000,...,n5=n5:6000 --storage /var/lib/ratis-kv`, stdout →
   `/var/log/ratis-kv.log`), then assert:
   a. the DESIGN §2.6 startup line appears in all five logs within a
      deadline;
   b. exactly one node's log shows a Ratis role change to LEADER within
      a deadline (grep the Ratis server log output — find the precise
      phrasing by observation and quote it in your report);
   c. port 6000 is listening on all five nodes;
   d. stop the five servers cleanly (SIGTERM, then verify exit).
   Exit 0 only if all hold; print each check's evidence line.
6. **`env/README.md`** — how to use `run.sh`/`validate.sh`; restate the
   deployment-contract table (copy from DESIGN §2.6, cite it as the
   source of truth); note arm64/x86_64 support status as tested.
7. **`jobs/02-env/02_report.md`** per `jobs/README.md`.

## File ownership

May create/modify: `env/**`, `jobs/02-env/02_report.md`, plus **one
appended block in `.gitignore`** for `env/.state/`. Nothing else — in
particular, nothing under `sut/**` or `harness/**` (Job 03 runs in
parallel with you in `harness/**`).

**Parallel-safe with: Job 03.**

## Acceptance criteria (each with command + output excerpt in your report)

1. `env/run.sh up` from a clean checkout reaches "all 7 nodes
   ssh-ready" (show the readiness output).
2. `ssh root@n3 true` (and one more node) from inside control exits 0.
3. `env/validate.sh` exits 0; report shows the evidence lines for
   5a–5d, including the observed leader-election log line quoted
   verbatim.
4. `env/run.sh down` then `docker ps --filter <your label/prefix>`
   shows nothing; a second `down` is a no-op exit 0.
5. `env/run.sh up` again after `down` works (no state leakage between
   cycles).
6. Image builds on your architecture; the Dockerfile contains no
   arch-pinned artifact URLs (show how JDK/Clojure install selects per
   arch).
7. Apache-2.0 headers on scripts (shell-comment form) and Dockerfile;
   no secrets or `env/.state/` contents committed; ownership respected.
8. `jobs/02-env/02_report.md` present per `jobs/README.md`.

## Non-goals

The Clojure harness itself, any Jepsen invocation, nemeses, CI
workflows, lazyfs/FUSE, TLS, `n6`/`n7` running servers (they stay
dormant), performance tuning, and any change to `sut/**`.

## Notes

- Startup-line await and log path are the same signals `db.clj` (Job
  03) will use — if you find the contract unimplementable as written,
  that is a Deviations-section finding, not something to silently adapt.
- Compose service names must equal hostnames `n1..n7` exactly (the
  harness's node list is those literal names).
- Keep the image lean-ish but do not golf it; build clarity beats size.
