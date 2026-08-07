# Job 16 report — pre-offer presentation, licensing, and provenance

## Summary

This job executes the presentation/licensing/disclosure half of the
Job 15 capstone's required-before-offer list: the false upstream-filings
sentence is replaced (in both places it existed) with the accurate and
better story, canonical `LICENSE` and a `NOTICE` now sit at the
repository root (closing BACKLOG 15), the README gains a
"Provenance, and what is on offer" section stating plainly that one
human owner directed AI agent sessions under the documented
job/review process, and the Quickstart now warns TLS-inspected-network
users toward `RJ_EXTRA_CA_BUNDLE` before they hit the raw failure. The
two decisions a reviewer should look hardest at: the provenance
section's placement and wording (directly after **Status**, before any
technical claims — and every sentence in it was checked against
`docs/PROCESS.md`, the briefs, and the merged record), and the
offer-scope paragraph, which is drafted deliberately as
**owner-confirmable** text, not a settled commitment.

## What was built

- `README.md` — five edits: (1) Quickstart paragraph now names
  `RJ_EXTRA_CA_BUNDLE` and the failure it prevents, pointing at
  `env/README.md`; (2) new section **"Provenance, and what is on
  offer"** after Status; (3) the CI section's durability-token sentence
  no longer claims "~2 minutes" lazyfs build cost (stale per capstone
  §3.6) and instead states the three true reasons the tokens are
  opt-in; (4) the "Findings about Ratis" filings paragraph rewritten
  (old/new quoted below); (5) License section now links `LICENSE` and
  `NOTICE`.
- `LICENSE` — new; the canonical Apache-2.0 text, byte-identical to
  `https://www.apache.org/licenses/LICENSE-2.0.txt` (sha256
  `cfc7749b…523d30` matches).
- `NOTICE` — new; project attribution, the sofa-jraft-jepsen
  shape-only prior-art credit (per PLAN Q17 and the brief), and a
  fetched-not-redistributed note naming the major dependencies with
  their licenses (Ratis Apache-2.0; Jepsen/knossos/Clojure EPL-1.0;
  lazyfs MIT © 2023 INESC TEC — each verified against the upstream
  license file or published artifact metadata during this job).
- `docs/BACKLOG.md` — item 15 marked **[CLOSED — Job 16 (2026-08-07)
  added both files]**, history preserved per the never-delete
  convention, with what was added and the capstone re-report noted.
- `docs/PLAN.md` — the §7 status parenthetical's "nothing has been
  filed against Ratis" corrected to scope the true statement to
  BACKLOG 7–10 and cite RATIS-2640/apache-ratis#1543 (already present
  in PLAN §8's references, which the old sentence contradicted).
- `jobs/16-pre-offer-presentation/16_report.md` — this report.

## How it was verified

**Acceptance 1 — every instance of the filings claim accurate; old and
new text; where I searched.**

Searched with `grep -rn 'filed\|filing' README.md docs/` (my ownership;
`jobs/`, `reviews/`, `results/`, code trees are outside it — see
Deviations). Hits and disposition:

- `README.md` (the false claim). Old text, verbatim:

  > No upstream issues have been filed against Ratis from this work. Two
  > bugs *were* filed against lazyfs ([#15], [#16]) while building M4.

  New text (abridged; full paragraph in the README):

  > Upstream filing status, precisely: **one Ratis issue has been filed
  > and fixed from this line of work** — [RATIS-2640]
  > (`AdminApi.setConfiguration(RaftPeer[], RaftPeer[])` drops the
  > servers array and always throws), found during the library
  > evaluation that preceded this harness and filed by this
  > repository's owner on 2026-08-04, fix submitted alongside it
  > ([apache/ratis#1543], merged the same day). … The four findings
  > *above*, by contrast, have **not** been filed upstream as of
  > 2026-08-07; … filing them is in the owner's hands. Two bugs *were*
  > filed against lazyfs ([#15], [#16]) while building M4.

  The facts in the new text were re-verified during Jobs 15/16:
  RATIS-2640 reporter and dates via Apache JIRA; apache/ratis#1543
  title/author/merged-state (merged 2026-08-04) via its public GitHub
  page during this job.

- `docs/PLAN.md:355` (same claim, status parenthetical). Old:
  "nothing has been filed against Ratis". New: "none of BACKLOG 7–10
  has been filed against Ratis; the one Ratis filing so far
  (RATIS-2640 with its merged fix apache/ratis#1543, §8) came from the
  preceding evaluation work, not from the harness."
- `docs/PLAN.md:21` — RATIS-2542 "filed 2026-05-21" by upstream:
  accurate, unrelated; left.
- `docs/PLAN.md` §2.3 decision text ("no upstream engagement yet",
  dated 2026-08-04) — a dated decision record, not a status claim;
  left intact, with the corrected §7 status sentence now carrying the
  precise current state.
- `docs/BACKLOG.md:41` — "nothing is filed" scoped to item 5's
  snapshot-API misreporting (no clean repro): accurate; left.

Post-edit re-run of the same grep: the remaining seven hits are all
accurate statements (listed above plus the new README paragraph
itself).

**Acceptance 2 — LICENSE and NOTICE present and correct; backlog
closed.**

```
$ sha256sum LICENSE ; curl -sS https://www.apache.org/licenses/LICENSE-2.0.txt | sha256sum
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  LICENSE
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  -
```

NOTICE license facts checked this job: lazyfs `LICENSE` at
dsrhaslab/lazyfs master reads MIT, Copyright (C) 2023 INESC TEC;
Jepsen's published artifact metadata (clojars.org/api/artifacts/
jepsen/jepsen) declares Eclipse Public License (epl-v10 URL); Clojure
and knossos are EPL-1.0; Ratis is Apache-2.0. BACKLOG 15 now opens
with `[CLOSED — Job 16 (2026-08-07) added both files]` and names this
job.

**Acceptance 3 — provenance section present, discoverable, accurate.**

Placed as the third section of the README ("Provenance, and what is on
offer"), immediately after **Status** and before "What it tests, and
how" — a maintainer reading top-to-bottom meets it before any
technical claim, which is the capstone's discoverability requirement.
Every claim in it maps to a source: roles and merge rules →
`docs/PROCESS.md` (Roles; Lifecycle; Branch and merge rules);
"twelve implementation jobs each verified by an independent reviewer
session" → the existing Status line and `reviews/01…12`; "REVISE
rounds … one of which caught an outcome-classification unsoundness" →
`reviews/05-nemesis-breadth/05_report.md` (the false-red discovery)
and the amended DESIGN §2.4; "jobs 13–15 merged without a separate
review, as their briefs and PROCESS.md's standing-jobs provision
record" → checked against `jobs/13…/13_brief.md` ("without a separate
review"), `jobs/14…/14_brief.md` ("Merge process for this job (no
review)"), `jobs/15…/15_brief.md` ("No review job follows"); "the
owner dispatched the published CI sweeps" → both Actions runs'
actor, verified via the GitHub API during Job 15; the Co-Authored-By
statement → `git log` trailers. The section deliberately names the
tooling generically ("Anthropic Claude, running in Claude Code") and
defers per-commit model identity to the trailers, which are the
authoritative record.

**Acceptance 4 — offer-scope statement drafted and flagged.**

Drafted as the closing paragraph of the provenance section, marked in
the README text itself as "*(scope statement drafted 2026-08-07; the
owner will finalize the wording at offer time)*". **Owner-confirmable
items:** (a) whether `jobs/` + `reviews/` ship with the donation or
are archived; (b) the "kept, trimmed, or archived at the receiving
project's preference" framing. Both are drafted as offers of
flexibility, not commitments.

**Acceptance 5 — Quickstart proxy note; stale claims corrected.**

Quickstart paragraph now reads "Behind a TLS-inspecting proxy
(corporate/CI egress), first point `RJ_EXTRA_CA_BUNDLE` at your
proxy's CA certificate(s) — otherwise the image build fails partway
with a raw `git`/TLS certificate-verification error…" and links
`env/README.md`. This is exactly the failure the capstone hit and the
knob it used successfully. The stale "~2 minutes" lazyfs cost claim in
the README's CI section is gone; the replacement states only verified
facts (not in the default gate set; amd64-only build stage; per-node
mount setup and proof) and no longer asserts a build-cost rationale
that the capstone's CI measurements (topology-up ≈76 s including the
lazyfs stage) contradicted. The workflow file's own version of that
comment is `.github/**` — Job 17's ownership — and was left alone.

**Acceptance 6 — no factual drift.**

Every touched statement cross-checked: the new filings paragraph
against JIRA/GitHub and `docs/BACKLOG.md` items 7–10 classifications
(unchanged); the provenance section against PROCESS.md and the three
no-review briefs; the durability-token sentence against the workflow's
default list and `env/README.md`'s amd64 note; the License section
against the actual new files. `docs/RUNS.md` and the results READMEs
were not modified.

**Acceptance 7 — this report**, in the `jobs/README.md` format.

## Deviations from the brief

- The brief says to check "the whole repository" for the filings claim.
  My ownership excludes `jobs/**` (other than this directory),
  `reviews/**`, `results/**`, and the code trees. I searched the whole
  repository anyway (`grep -rn` from the root): outside my ownership
  the matches are `harness/src/ratis_jepsen/client.clj`'s "RATIS-2640,
  our upstream find" comment — which is *accurate* (a find of this
  line of work, filed and fixed) and lives in Job 17's tree — and
  `jobs/13-docs-refresh/13_report.md`, which *describes* the sentence
  Job 13 wrote at its date. Historical job/review reports are the
  immutable record and were left untouched (the capstone's own report
  quotes the old sentence in order to convict it).
- "correct any stale cost/claim comments the capstone identified in
  documentation you own": of the capstone's two stale-cost locations,
  only the README sentence is mine; the `jepsen.yml` comment (capstone
  §3.6) is Job 17's. Noted here so it isn't lost between the two jobs.

## Known gaps and risks

- The offer-scope wording and the decision about shipping the process
  record are **explicitly owner-confirmable**; the README says so in
  place. If the owner's decision differs, only that one paragraph needs
  editing.
- The provenance section characterizes the review record qualitatively
  ("includes REVISE rounds") rather than with counts, to avoid a number
  that drifts; the precise record remains in `reviews/`.
- NOTICE names dependency licenses as of 2026-08-07 (jepsen EPL-1.0,
  lazyfs MIT, etc.). If the dependency set changes, NOTICE should be
  revisited — cheap, but nothing enforces it.
- If Job 17 edits the `jepsen.yml` durability comment to a *different*
  factual framing than the README's new sentence, the two should be
  reconciled at merge; both are now accurate but independently worded.

## Suggestions (out of scope)

- The capstone's §6 suggested one sentence in the RC2 results README
  noting that verbatim re-runs stop being possible when the staging
  repository is dropped after the vote; `results/**` was outside this
  job's ownership, so it remains open.
- PLAN §2.3's dated decision text ("no upstream engagement yet",
  2026-08-04) now sits adjacent to a same-day upstream filing from the
  preceding evaluation. A one-line dated annotation there would remove
  the last possible reading of inconsistency; I left dated decision
  records untouched by policy.
