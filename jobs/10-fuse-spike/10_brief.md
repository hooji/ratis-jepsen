# Job 10 — the FUSE spike (gates M4)

*Coordinator brief, 2026-08-06.*

**Read `jobs/README.md` first — binding.** Then `docs/PLAN.md` Q7(c)
and M4. Base on current `main` (M3 complete). **This is a spike, not a
feature**: the deliverable is a *settled answer with evidence*, sized
at roughly a day. No harness/env/SUT changes.

## The question

Can [lazyfs](https://github.com/dsrhaslab/lazyfs) (FUSE-based
lost-write simulation) run inside our privileged containers **on
GitHub-hosted runners** — mount, intercept writes on a
Ratis-storage-shaped directory, drop un-fsynced data on command —
so M4 can target CI? Or does M4 need a self-hosted/VM runner?

## Deliverables

1. **Local proof first**: in our `env/` container image (unmodified —
   run experiments via `docker exec`/ad-hoc containers), build/install
   lazyfs, mount it over a scratch dir, write through it, trigger its
   fault (drop un-fsynced pages), and demonstrate the loss with
   before/after file contents. Record every command.
2. **GHA proof**: a throwaway workflow
   `.github/workflows/fuse-spike.yml` (`workflow_dispatch` only,
   clearly commented as a spike artifact) that runs the same
   experiment on a hosted runner — including `/dev/fuse` availability
   inside the privileged container. Dispatch it from your branch; link
   the run.
3. **The SUT-shaped rehearsal** (the part that de-risks M4's design):
   still in the spike, mount lazyfs over a scratch dir, run a
   **single-node** `ratis-kv` with `--storage` on it, write a few
   entries, drop un-fsynced data, restart, and record what Ratis does
   (clean recovery / corruption error / silent truncation — whatever
   happens is the finding; do not fix anything).
4. **The verdict** in `jobs/10-fuse-spike/10_report.md`: CI-viable or
   not; version/commit of lazyfs used; arch notes (runner is x86_64 —
   state whether your local arm64 attempt worked, per PLAN Q8
   lazyfs-x86-only expectation); the observed Ratis
   behavior-under-loss; and a recommended M4 shape (where it runs,
   what the nemesis interface should look like: mount lifecycle,
   fault trigger, per-node vs per-dir).

## File ownership

`.github/workflows/fuse-spike.yml` (new, throwaway, so marked),
`jobs/10-fuse-spike/10_report.md`. Nothing else — experiments run in
containers/scratch space, not committed.

## Acceptance criteria

1. Local loss demonstration with command transcript.
2. GHA run linked, green (or a definitive documented failure — a
   proven "no" is a valid spike outcome and changes M4's runner, not
   the verdict's quality).
3. Ratis-under-loss observation recorded with logs quoted.
4. Report per `jobs/README.md`, with the M4 recommendation.

## Non-goals

Any nemesis implementation, env/Dockerfile changes, multi-node
lazyfs, harness integration, fixing whatever Ratis does under loss.
