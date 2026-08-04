# ratis-jepsen

A from-scratch Jepsen test harness for Apache Ratis (Java Raft library),
built by a coordinated multi-agent process.

## Orientation by role

- **Assigned a job** (implement something)? Read `jobs/README.md` first,
  then your brief in `jobs/<NN>-<slug>/`.
- **Assigned a review**? Read `reviews/README.md` first, then your brief
  in `reviews/<NN>-<slug>/`.
- **Coordinating / anything else**: `docs/PROCESS.md` (workflow),
  `docs/PLAN.md` (project plan + decisions), `docs/DESIGN.md` (M0 design).

## Hard rules for all agents

- Never push to `main` — only the coordinator merges. Sole exception:
  reviewers squash-merge their own report-only review PR (see
  `reviews/README.md`).
- Stay on your auto-assigned branch; never push to anyone else's branch.
- Stay inside your brief's declared file ownership.
- Apache-2.0 headers on all new source files; no build/run artifacts in
  commits (see `.gitignore`).
