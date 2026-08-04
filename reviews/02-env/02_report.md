# Review 02 report — Job 02: `env/` — containerized cluster topology and entry point

Worker PR: #3 (`claude/sut-brief-instructions-jfwibr`, head `e9ee12b`, base
`dd5a3df`). Reviewed in a detached worktree at that commit with a real
Docker daemon (Engine 29.3.1 / Compose v5.1.1, Linux x86_64, 4 CPUs); every
command below was run by me. Like the worker, I run behind this sandbox's
TLS-inspecting egress, so all `up`/`validate` invocations set
`RJ_EXTRA_CA_BUNDLE=<single proxy root CA pem>`; on unproxied hosts the
variable is unnecessary. The worker's code was not modified; probes used
only runtime state and the script's own env knobs.

## Verdict: REVISE

## Justification

Seven of the eight acceptance criteria verify cleanly, and the build is
genuinely good: the topology, ssh wiring, teardown scoping, contract
fidelity, and stub structure all check out under both idle and stressed
runs, and `validate.sh` went green four out of four times undisturbed
(twice under 2× CPU oversubscription). The one blocking finding is the
review brief's pre-declared robustness bar for check (b): the
"exactly one LEADER" assertion counts *election history* rather than
*current leadership*, so any legitimate in-window re-election fails a
healthy cluster. This is not hypothetical: I demonstrated it with a
4-second scheduling stall on the leader (the pause profile of a GC or a
noisy CI neighbor), after which the cluster was demonstrably healthy —
exactly one current leader at term 2, all five ports listening — yet
`validate.sh` exited 1 with `expected exactly one leader, found 2`. The
worker's own Known-gaps section flags this and even names the fix; the
job brief's literal wording invited the weak check, but this script
becomes CI's first gate in M1, so per the review brief it must not be
able to condemn a healthy cluster. The required revision is small and
fully specified below.

## What I verified

**Criterion 1 — `up` from a clean checkout.** First `up` built the image
(Ubuntu 24.04 multi-arch base, apt OpenJDK 21, Clojure CLI 1.12.1.1550 via
the official installer) and reached readiness, generating the keypair on
first run:

```
run.sh: generated ssh keypair at .../env/.state/ssh/id_ed25519[.pub]
run.sh: n1 ssh-ready ... run.sh: n7 ssh-ready
run.sh: all 7 nodes ssh-ready          EXIT=0
```

**Criterion 2 — non-interactive root ssh from control.**

```
$ docker compose -f env/docker-compose.yml exec -T control ssh root@n3 true; echo $?   → 0
$ docker compose -f env/docker-compose.yml exec -T control ssh root@n7 true; echo $?   → 0
```

**Criterion 3 — `validate.sh` exits 0 with evidence.** Four undisturbed
runs, all green: two idle (one executed from `cwd=/` — the script is
cwd-independent), two with the host CPUs 2× oversubscribed by busy-loops.
Leaders varied across runs (n1, n1, n1, n2) — real election variance
sampled. Evidence lines from run 1:

```
validate:   [n1] ... ratis-kv server started: id=n1 address=n1:6000 storage=/var/lib/ratis-kv group=group-ABBC16E54704 peers={n1=n1:6000, n2=n2:6000, n3=n3:6000, n4=n4:6000, n5=n5:6000}
validate: PASS (a): startup line present on all five nodes
validate:   [n1] ... [n1@group-ABBC16E54704-LeaderElection1] INFO org.apache.ratis.server.RaftServer$Division - n1@group-ABBC16E54704: changes role from CANDIDATE to LEADER at term 1 for changeToLeader
validate: PASS (b): exactly one node (n1) became LEADER
validate:   [n1] LISTEN 0      4096         0.0.0.0:6000       0.0.0.0:*
validate: PASS (c): port 6000 listening on all five nodes
validate:   [n1] ... [ratis-kv-shutdown] INFO ratis.jepsen.kv.Main - ratis-kv server n1 shutting down
validate: PASS (d): all five servers stopped cleanly on SIGTERM
validate: ALL CHECKS PASSED                     exit 0
```

**Criterion 4 — `down` cleans up; second `down` no-op.** After `down`:
project containers 0, the `jepsen` network and `maven-repo` volume
removed; a second `down` exits 0. A **canary container** I ran from the
same image but outside the compose project survived every `down`
untouched — teardown is provably project-scoped (emphasis 3).

**Criterion 5 — `up` after `down`.** Green (`all 7 nodes ssh-ready`,
exit 0); cycles share nothing but the deliberately-persistent keypair.

**Criterion 6 — arch neutrality.** Built and fully validated on x86_64
only (arm64 = inspection-verified, matching the worker's honest claim).
`grep -Ei "amd64|x86_64|arm64|aarch64" env/Dockerfile` → no matches. I
additionally fetched the pinned Clojure installer script itself: it
contains **no** `uname`/arch branching — it downloads a single
arch-independent `clojure-tools-1.12.1.1550.tar.gz`, so the whole stack
is arch-neutral by construction (base manifest + apt + pure-JVM tools).

**Criterion 7 — hygiene.** Apache-2.0 shell-comment headers on
`Dockerfile`, `docker-compose.yml`, `ssh_config`, `run.sh`, `validate.sh`
(README.md/.dockerignore exempt per repo convention and the criterion's
wording). No key-like material anywhere in the diff; no `env/.state/`
content committed; `git check-ignore env/.state/ssh/id_ed25519` confirms
the ignore. Diff touches only `env/**`, the single `.gitignore` block,
and the job report — ownership respected.

**Criterion 8 — report.** Present, all six sections in order; claimed
outputs reproduced (readiness lines, validate evidence, teardown counts).

### Review-brief emphasis

**1. SSH/key hygiene.** Keys are generated per-checkout under gitignored
(and dockerignored) `env/.state/`; the private key mounts read-only into
`control` only, the public key into nodes as `authorized_keys`. Host-key
relaxations live in `env/ssh_config`, which is COPY'd **into the image**
(never referenced by the host's own ssh) and scoped to
`Host n1 n2 n3 n4 n5 n6 n7` — not a wildcard. Proof of scope: from
control, `ssh root@control` fails host-key verification (control is not
in the Host list, and its sshd has no authorized key mounted — inbound
root to control is impossible). Key file is chmod 600 in-container.
Regeneration: deleting `env/.state/` while the cluster runs, then
`run.sh up`, regenerates the pair and compose **recreates all containers**
against the new files — fingerprints on disk, in control, and in nodes'
`authorized_keys` all match afterward (verified via `ssh-keygen -lf`);
no stale-mount mismatch, no wedge.

**2. Leader-check robustness — the blocking finding.**
(a) The grep pattern `changes role from .* to LEADER` matches the real
Ratis 3.2.2 line; observed verbatim on my runs:

```
n1@group-ABBC16E54704: changes role from CANDIDATE to LEADER at term 1 for changeToLeader
```

(b) The "exactly one" assertion is over *any node whose log has ever
contained* a LEADER transition. Undisturbed it passed 4/4 (incl. 2 under
CPU load). But the failure mechanism is structural, and I demonstrated it
concretely using only the script's own knob: with `RJ_LEADER_SETTLE=25`
(default 5 — same code path, wider observation window), I paused the
elected leader's JVM for 4 s during the settle (`pkill -STOP`/`-CONT` —
the stall profile of a long GC pause or CI noisy neighbor; Raft is
designed to ride these out). n2 won term 2; n1 resumed and stepped down
cleanly. The cluster at recount time was **healthy** — per-node *last*
role-transition lines:

```
n1: changes role from    LEADER to FOLLOWER at term 1 for StepDownReason:LOST_MAJORITY_HEARTBEATS
n2: changes role from CANDIDATE to LEADER at term 2 for changeToLeader
n3/n4/n5: FOLLOWER→FOLLOWER (voted for candidate n2)          port 6000: listening on all five
```

— yet `validate.sh` exited 1 with
`validate: FAIL: (b) expected exactly one leader, found 2: n1 n2`.
A checker that can condemn a healthy cluster is a flaky CI gate by
construction; the default 5 s settle only shrinks the window (boot→first
LEADER line + settle + ~5 s of recount greps), it does not close it, and
the same double-history arises with no reviewer intervention whenever a
boot-window re-election happens (the worker's Known-gaps says the same).
Required revision below.

**3. Teardown safety.** Compose project `ratis-jepsen`, pinned
`ratis-jepsen-*` container names, project-scoped network/volume;
`down --volumes --remove-orphans` removed exactly those and nothing else
(canary from the same image, outside the project, survived repeatedly);
idempotent second `down`; `docker ps -a` clean after.

**4. Multi-arch honesty.** Tested architecture: **x86_64 only** (mine and
the worker's). arm64 remains untested-by-execution; the Dockerfile and
installer inspection above is the full extent of the evidence, and the
README says exactly that. Honest and acceptable.

**5. Contract fidelity (DESIGN §2.6).** Checked literally in
`validate.sh`/`docker-compose.yml`: install dir `/opt/ratis-kv`
(`bin/ratis-kv` + `lib/`), storage `/var/lib/ratis-kv`, log
`/var/log/ratis-kv.log` (stdout-redirect), port `6000` in every peer
entry, user `root`, service names = hostnames `n1..n7`, `n6`/`n7` up but
serving nothing. Startup-line await uses the §2.6 string
(`ratis-kv server started:`) and the full observed line matches the
contract field-for-field. No drift for Job 03/04 to trip on.

**6. `test` stub.** `run.sh test` prints the Job-04 message and exits
**64**; the body sits between `BEGIN Job-04 stub`/`END Job-04 stub`
markers inside `cmd_test()` so Job 04's replacement is local. No-arg and
unknown subcommands exit 2 with usage; `--help` exits 0.

### Probes beyond the worker's runs (reviews/README rule 3)

1. **Leader stall** (above) — produced the blocking finding.
2. **`docker kill n4` mid-validate**: the run aborted in ~1 s, exit 255,
   with `ssh: Could not resolve hostname n4: Name or service not known`
   — non-zero, names the node, no hang. Nit: when a node dies *between*
   check loops, the abort comes from `set -e` with the raw ssh error
   only; inside a check loop you get the polished named
   `validate: FAIL` (both shapes observed).
3. **Delete `env/.state/` while up** (with n4 still dead from probe 2):
   next `run.sh up` regenerated the keypair, recreated all containers
   consistently, and reached `all 7 nodes ssh-ready` — recovery, not a
   wedge (fingerprint evidence under emphasis 1).
4. **`validate.sh` from a foreign cwd** (`/`): green.
5. **Teardown canary**: survived every `down` (emphasis 3).

## Findings

| # | Severity | File:line | Finding |
|---|---|---|---|
| 1 | **blocking** | `env/validate.sh:120-142` | Check (b) counts nodes whose log *ever* contained a LEADER transition, so a legitimate boot-window re-election (leader pause > election timeout from GC/CPU-steal, split-vote churn) leaves two matching nodes and fails a **healthy** cluster. Demonstrated: 4 s leader stall → cluster recovers with exactly one current leader (n2, term 2) → `FAIL: (b) expected exactly one leader, found 2: n1 n2`, exit 1. This script is slated to be CI's first gate (M1); per the review brief this robustness class is REVISE-grade. Worker's Known-gaps acknowledges the flake and names the stricter alternative. |
| 2 | non-blocking | `env/Dockerfile:46-51`, `env/README.md:31` | `EXTRA_CA_B64` with a **multi-cert** PEM is only half-trusted: `update-ca-certificates` puts every cert into the system store (curl/Clojure fine), but Debian's `jks-keystore` hook imports only the **first** cert of the file into the JVM keystore — verified in a fresh container: exactly one alias `debian:ratis-jepsen-extra-ca.pem` whose subject is the bundle's first cert. Result with a multi-cert bundle whose needed root isn't first: image builds, `validate.sh` later dies at the in-control Maven step with `PKIX path building failed` (reproduced). Single-cert bundles (the worker's usage, and mine after diagnosis) work end-to-end. README says "CA(s)" and the Dockerfile comment claims full JVM trust — both slightly overpromise. Fix: split the decoded bundle one-cert-per-file into `/usr/local/share/ca-certificates/` before `update-ca-certificates`. |
| 3 | non-blocking | `env/run.sh:62` | An oversized `RJ_EXTRA_CA_BUNDLE` (e.g. a full system bundle) fails as a raw `docker: Argument list too long` (single-arg kernel cap ~128 KiB). README documents "pass just the extra CA(s)"; a pre-flight size check with a friendly message would turn a cryptic error into an instruction. |
| 4 | non-blocking | `env/validate.sh:26` | On a node dying *between* check loops, `set -e` aborts with only the raw ssh error (still non-zero, still names the node, still fast) — no `validate: FAIL` summary/log-dump. A `trap ... ERR` printing the failing step would make every failure shape uniform. |
| 5 | non-blocking | `env/run.sh:49-52` | Container-fallback keygen writes `env/.state/ssh/*` as root; on a non-root dev machine the files come out root-owned (host `ssh-keygen` is preferred when present, so dev machines rarely hit it). Worker's report already notes the general root-owned-files caveat for the repo mount. |

## Required revisions

1. **`env/validate.sh` — make check (b) assert current leadership, not
   election history.** Change `count_leaders()` to count a node iff its
   *last* role-transition line is a transition **to LEADER** — e.g. per
   node: `grep 'changes role from' ${LOG_FILE} | tail -n 1` matched
   against `to LEADER` (the existing `LEADER_PATTERN`). Keep the settle
   sleep; then, instead of hard-failing on a single sample, keep
   re-sampling inside the existing `LEADER_DEADLINE` until the count is
   exactly 1 (a sample taken mid-handover may legitimately be 0 or 2);
   fail only at deadline, dumping each node's last role-transition line
   as evidence. Keep printing the winning node's LEADER line verbatim
   (the report's quoted-line requirement). Update the stale
   "Leader-uniqueness check can flake by design" entry in
   `jobs/02-env/02_report.md`'s Known gaps (append a Revision 1 note per
   `jobs/README.md`; don't rewrite history), and adjust the check's
   description in `env/validate.sh`'s header comment and `env/README.md`
   if it names the semantics. My stall scenario (leader paused 4 s during
   settle, `RJ_LEADER_SETTLE=25`) must then pass: after the revision the
   recount sees n1's last line `LEADER to FOLLOWER` and n2's
   `CANDIDATE to LEADER` → count 1 → green.

## Suggestions (non-blocking)

1. Split multi-cert `EXTRA_CA_B64` bundles one-cert-per-file in the
   Dockerfile CA block (a short awk/csplit loop into
   `/usr/local/share/ca-certificates/rj-extra-NN.crt`) so system and JVM
   stores agree for any bundle shape; align the README's "CA(s)" wording
   and the Dockerfile's JVM-trust comment with whatever is implemented
   (Finding 2).
2. Pre-flight size check for `RJ_EXTRA_CA_BUNDLE` in `run.sh` with a
   message pointing at the README's single-CA guidance (Finding 3).
3. A `trap`-based failure summary in `validate.sh` so between-check
   aborts also print the failing step and a log tail (Finding 4).
4. The `maven-repo` named volume surviving container recreation while
   `down --volumes` removes it is exactly the right lifecycle — worth one
   README sentence, since it explains why the second validate in a cycle
   is fast but cycles stay hermetic.
5. Environment note for future agent sessions (no repo change needed):
   in this sandbox the *container* egress MITM chain is anchored by the
   host bundle's `sandbox-egress-gateway-production Egress Gateway CA` —
   the single right value for `RJ_EXTRA_CA_BUNDLE` here. Passing the full
   152-cert system bundle hits Finding 3; passing a multi-cert subset
   hits Finding 2.
