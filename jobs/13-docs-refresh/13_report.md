# Job 13 report — documentation refresh (run 1)

Run 1 of the standing documentation job, worked from `main` at `95acae2`
("Record Job 12 outcomes in the backlog"), i.e. after Jobs 01–12 and
Reviews 01–12 had merged.

## Summary

Ground truth was established from the repository first — the twelve
merged job reports and twelve review reports, `docs/RUNS.md`, both
workflow files, and the source tree (nemesis kinds, workloads, checkers,
CLI options, SUT launcher flags, `env/run.sh` subcommands) — and every
prose document was then audited against that inventory. Two documents
were rewritten because they described a repository that no longer exists
(`README.md`, stale at M0; `harness/README.md`, stale at Job 03's
skeleton). The rest were amended in place: `docs/PLAN.md` and
`docs/DESIGN.md` gained dated amendments beside the original text rather
than edits to it, `docs/BACKLOG.md` gained closure marks on four items
whose milestone has arrived plus one new open item, and `docs/PROCESS.md`
gained the standing-job rule it was already operating under. The two
judgment calls worth checking hardest: I declare **M5 complete** (Job 12
delivered both halves of M5 as scoped in PLAN §5 and merged with a MERGE
verdict, but no coordinator commit says the words), and I record in the
backlog — rather than fix — the **missing `LICENSE`/`NOTICE`**, which is
outside this job's `*.md` ownership.

## What was built — the discrepancy inventory

Evidence column names what the claim was checked against. "OK" findings
are not listed; every document was read in full.

### `README.md` — rewritten

| # | Stale claim | Evidence |
|---|---|---|
| 1 | "**M0 (walking skeleton) complete** … M1 … is in progress" | Jobs 01–12 merged (`git log`); PLAN §7; twelve review reports |
| 2 | Status section described only the two M0 reference runs | `docs/RUNS.md` carries M0–M5 gate tables |
| 3 | Quickstart implied `--nemesis partition\|none` and `--seed-bug` were the option surface | `nemesis/kinds` has 16 kinds; `core.clj` has ~25 options incl. `--workload`, `--reads`, `--ratis-version`, `--mixed-version`, `--durability` |
| 4 | CI section: "A dispatch takes a comma-separated `scenarios` list (default `none,partition`) plus a `time-limit`" | `jepsen.yml` default is the 11-token list; inputs also include `ratis-version`, `mixed-version`, `ratis-repo-url` |
| 5 | Nothing about the durability or version dimensions, the workloads, the checkers, or what has been found | brief's README standard; `checker.clj`; `docs/BACKLOG.md` |
| 6 | Pointers table omitted `docs/BACKLOG.md`, `harness/README.md`, `env/README.md`, `jobs/`, `reviews/` | file tree |
| 7 | "the M0 design" as DESIGN's whole description | DESIGN now carries amendments through M5 |

The rewrite is aimed at a Ratis maintainer who has never seen the repo:
what it is, workloads/nemeses/checkers including the durability and
version dimensions, how to run it locally and in CI, what it has
demonstrated, the findings *with their backlog classifications*, and a
"Known limits" section (knossos not elle, 300 s run scale, lazyfs's
model, x86_64-only validation, the probe-hardening caveat, scope).
Findings phrasing follows BACKLOG exactly: 7 and 9 are reproduced
defects, 8 is already fixed upstream and framed as "the test that keeps
it fixed", 10 is stated as an open **question with a mechanism** that
this harness cannot demonstrate, and the metadata result is
"source-proven; probe-consistent" — never "experimentally confirmed".
It also states plainly that nothing has been filed against Ratis, and
that the two filed bugs are against lazyfs.

### `harness/README.md` — rewritten

| # | Stale claim | Evidence |
|---|---|---|
| 8 | "Job 03 deliverable… There are no workloads, checkers or nemeses yet — Job 04 brings those" | `src/` has `nemesis.clj`, `checker.clj`, `workload/{register,counter}.clj` |
| 9 | Layout listed 5 src + 4 test files | 9 src files, 7 test files, plus `probe/` and `scripts/` |
| 10 | "client — … `noRetry`" | `client.clj` uses bounded same-callId retries (DESIGN §2.3's 2026-08-05 amendment); `noRetry` was the thing Review 05 disproved |
| 11 | "`clojure -M:run test --help` (usage only at this stage)" | the CLI is the real entry point |
| 12 | A "What arrives in Job 04" section | Job 04 merged 2026-08-04 |
| 13 | No mention of the two probes | `probe/…/lifecycle.clj`, `scripts/metadata-probe.sh` |

### `docs/PLAN.md` — amended (history preserved)

| # | Discrepancy | Fix |
|---|---|---|
| 14 | §7 status blob ended at "M5 underway: job 12" | new dated paragraph at the head of §7: M5 complete, Q15 discharged against RC2 rather than a release, M0–M5 all merged, elle + RocksDB still undone, upstream question still open |
| 15 | §3 non-goals still listed lazyfs and mixed-version topologies as not-yet-done | dated note recording both as delivered at M4/M5, with the remaining non-goals restated |
| 16 | §5 milestone list read as a forward plan | dated status note: M0–M5 complete, plus the three deviations from the plan (no elle, `quorum-pause` needed for Q14, M5 against RC2) |
| 17 | §6 leanings never reconciled with what was built (Q10's elle-as-primary most consequentially) | new **§6.G** amendment recording how Q6, Q10, Q11, Q12, Q13, Q14, Q15 and Q18 actually resolved; leanings left verbatim |

### `docs/DESIGN.md` — amended (history preserved)

| # | Discrepancy | Evidence / fix |
|---|---|---|
| 18 | No statement of the doc's own status, so readers can't tell history from live contract | header note: M0 design + dated amendments; §2.6 is live; `--help` and `outcome.clj` are authoritative for their surfaces |
| 19 | §1.2 process contract omitted `--join` and `--retry-cache-expiry-ms` | `ServerOptions.java`, `Main.usage()`; amendment added |
| 20 | §1.3 "M2 lowers it further to force install-snapshot" — **M2 did not** | `Main.buildProductionProperties` still sets 4096; Job 07 reached install-snapshot with a sustained write stream (`--rate 1.4 --ops-per-key 800`) instead. Tense corrected to "was expected to", amendment explains what actually works |
| 21 | §2.4 table missing five rows the implementation has | `outcome.clj` docstring: `ServerNotReadyException`, `RaftRetryFailureException` (null and non-null cause), `LeaderSteppingDownException`, harness-side `TimeoutException`. Amendment lists them and names `outcome.clj` as the live table |
| 22 | §2.5 "elle's rw-register replaces knossos as primary in M1" — it did not | `register.clj` composes `checker/linearizable` with `model/cas-register`; BACKLOG 13. Amendment states it plainly and lists the checkers that *were* added |
| 23 | §2.6 contract silent on the M4/M5 topology changes | two rows added: lazyfs mount over `/var/lib/ratis-kv.root` under `--durability`; `/opt/ratis-kv` as a per-node symlink under `--mixed-version` (`db.clj` `install-mixed!`) |
| 24 | §3 "One multi-arch image (arm64 dev / x86_64 CI)" and "n6/n7 … run no SUT" | `env/README.md` records arm64 as untested and lazyfs as amd64-only; `nemesis/membership-kinds` puts the SUT on n6/n7 for four kinds. Amendment added |
| 25 | §4 CI shape: inputs "`ratis-version`, `scenario`, `time-limit`", no red-gate | `jepsen.yml`. Amendment records the real inputs, the parse step, one tarball per version, and the red-gate job's double assertion |

### `docs/BACKLOG.md` — amended

| # | Discrepancy | Fix |
|---|---|---|
| 26 | Items 4, 5, 6 and 14 written as pending, but their milestones have arrived and been satisfied (Jobs 11, 07, 06, 06 respectively) | marked **[CLOSED]** in place with what closed them; item 5's Ratis-side observation explicitly kept open (no clean repro, nothing filed) |
| 27 | The missing `LICENSE`/`NOTICE` was raised by Job 06's Suggestions and tracked nowhere | new **item 15**, open, with the PLAN Q17 reference |
| 28 | No statement of what the file's marks mean | header note: nothing is deleted, `[CLOSED]` marks are explicit, items 7–11's classifications bind every document |

I made **no change** to the classification, wording or evidence of items
7–11. They were re-read against Jobs 08/11/12 and Reviews 08/11/12 and
are accurate as written, including the RC2 persistence results.

### `docs/RUNS.md` — structure only

| # | Discrepancy | Fix |
|---|---|---|
| 29 | Store names before the M5 section lack the `-ratis-<version>` suffix that all later ones carry, with nothing explaining why | one dated note under the header. **No run result, number, verdict or excerpt was touched.** |

### `env/README.md` — amended

| # | Discrepancy | Fix |
|---|---|---|
| 30 | Usage block omitted `run.sh probe`; `test`'s pass-through list predated `--workload`/`--ratis-version`/`--mixed-version` | both added, matching `run.sh`'s own header comment |
| 31 | "`n6`/`n7` are up but run no SUT in M0" | corrected: they run the SUT on the four membership-bearing kinds (`nemesis/membership-kinds`), and only those |
| 32 | Intro credited only Jobs 02/03/04 for a file that has since grown lazyfs and version selection | intro generalized; DESIGN §2.6's two new rows cross-referenced |

The knobs table, lazyfs section and architecture section were checked
against `run.sh`, `Dockerfile` and Job 11's report and are accurate —
unchanged.

### `docs/PROCESS.md`, `jobs/README.md`, `reviews/README.md`, `CLAUDE.md`

| # | Discrepancy | Fix |
|---|---|---|
| 33 | PROCESS's lifecycle had no place for a standing, re-runnable, documentation-only job merged without review — which is what Job 13 is | new subsection under the lifecycle recording the rule, including the report-numbering convention and the higher care bar |
| 34 | `jobs/README.md` described DESIGN as "the M0 design your job implements a slice of" and never pointed workers at RUNS or BACKLOG | reading list updated; a short paragraph added on where current state actually lives |
| 35 | `reviews/README.md` same, for reviewers who must judge how a result is characterized | reading list updated |
| 36 | `CLAUDE.md`'s orientation list omitted RUNS, BACKLOG and README | added |

PROCESS's roles, artifact layout, branch/merge rules, round-2 fast path,
parallelism and standards sections were verified against the merged
history (report/review filenames, squash-merge commit titles, the
reviewer-merges-own-report exception) and are accurate — unchanged.

## How it was verified

**Acceptance criterion 1 (inventory with evidence)** — the tables above;
36 items, each naming what it was checked against.

**Acceptance criterion 2 (each fixed or deferred)** — all 36 fixed. One
finding is deferred by necessity and recorded instead of fixed:

- The repository has **no `LICENSE` and no `NOTICE`** despite PLAN Q17,
  a public repo, and an ASF-donation endgame. This is a repository
  defect, not a documentation one, and the root files are outside this
  job's `*.md` ownership. Filed as BACKLOG 15 and reported here.

**Acceptance criteria 3–4 (README correctness, cross-document
consistency)** — every factual claim in the new README was taken from
one of: `nemesis.clj`'s `kinds`/`durability-kinds`/`membership-kinds`
sets, `core.clj`'s option vector and `workloads` map, `register.clj` /
`counter.clj`'s composed checkers, `client.clj`'s read routing,
`jepsen.yml`'s inputs and `red-gate` job, `docs/RUNS.md`'s gate tables,
and `docs/BACKLOG.md`'s classifications. Spot checks of the claims most
likely to drift:

```
$ grep -n 'def kinds' -A 8 harness/src/ratis_jepsen/nemesis.clj
(def kinds
  #{"none" "partition" "crash" "pause" "mixed"
    "snapshot-churn" "transfer" "membership" "membership-snapshot-churn"
    "listener-probe" "quorum-pause" "mixed-all"
    "unsync-drop" "unsync-drop-all" "torn-write"
    "rolling-upgrade"})
```

```
$ grep -n 'default:' .github/workflows/jepsen.yml
48: default: none,partition,crash,pause,mixed,snapshot-churn,transfer,membership,
         membership-snapshot-churn,mixed-all,counter-crash
```

```
$ grep -n 'membership-kinds' -A 4 harness/src/ratis_jepsen/nemesis.clj
(def membership-kinds
  #{"membership" "membership-snapshot-churn" "listener-probe" "mixed-all"})
```

```
$ grep -n 'ln :-sfn' harness/src/ratis_jepsen/db.clj
215:  (c/exec :ln :-sfn (version-install-dir active-version) env/install-dir)
225:    (c/exec :ln :-sfn dir env/install-dir)
```

Status claims now agree across README ("M0–M5 complete"), PLAN §5, PLAN
§7 and DESIGN's amendments; the nemesis list agrees across README,
`harness/README.md`, DESIGN's amendments and `nemesis.clj`; the version
support statement (3.2.2 default, 3.3.0 **RC2, not a completed release
as of 2026-08-07**) agrees across README, PLAN §7 and RUNS's M5 section;
findings classifications agree across README and BACKLOG verbatim.

**Acceptance criterion 5 (links resolve)** — every non-HTTP markdown
link target in every `*.md` in the repository was resolved against the
file tree:

```
$ grep -rhoE '\]\(([^)#]+)\)' --include='*.md' . | sed 's/](\(.*\))/\1/' \
    | grep -v '^http' | sort -u | while read -r l; do ... done
done          # no MISSING lines
```

The backtick-quoted paths introduced or retained in the refreshed
documents (`harness/scripts/metadata-probe.sh`,
`harness/probe/ratis_jepsen/probe/lifecycle.clj`,
`.github/workflows/jepsen.yml`, `harness/src/ratis_jepsen/outcome.clj`,
`env/validate.sh`, `sut/ratis-kv/src/main/dist/bin/ratis-kv`) were
existence-checked the same way.

**Acceptance criterion 6 (what I did not change)** — below.

No build or test was run: this job changes no source, config or
workflow, and its ownership forbids doing so.

## Deviations from the brief

1. **"M5 complete" is my inference, not a quotation.** No coordinator
   commit declares it. The basis: PLAN §5 scopes M5 as "run matrix over
   releases; mixed-version topology"; Job 12 delivered both and merged
   as `543d031` after Review 12's MERGE; the coordinator then recorded
   its outcomes in the backlog (`95acae2`). Every earlier milestone was
   declared complete on exactly that evidence. If the coordinator
   intends M5 to stay open pending 3.3.0's actual release, three
   sentences need reverting: the PLAN §7 head note, PLAN §5's status
   note, and the README's Status line.
2. **BACKLOG gained an item.** The brief allows fixing a stale backlog
   and saying so; item 15 (`LICENSE`/`NOTICE`) is an *addition* rather
   than a correction. It records a defect Job 06 raised and nothing
   tracked, which the README's license section would otherwise imply is
   handled.
3. **`docs/RUNS.md` got one added note and nothing else.** The brief
   restricted this file to structure/consistency; the store-naming note
   is the only structural gap I found worth stating, and I invented,
   altered and removed no results.

## Known gaps and risks

- **The README will drift first, and fastest.** Its nemesis table, CLI
  examples and CI default list are transcriptions of four moving
  sources. A future run of this job should re-check them against
  `nemesis/kinds`, `core.clj`'s option vector, `workloads`, and
  `jepsen.yml`'s `default:` lines before anything else.
- **DESIGN is now half history, half live contract**, and the split is
  stated only in its header note. §2.6 (plus the outcome-map and CI
  amendments) is what binds; everything else is the M0 record. If this
  becomes confusing for readers, the fix is a separate current-state
  document — a decision above this job's pay grade, so I did not make
  it.
- **"M0–M5 complete" is a milestone claim, not a quality claim.** Two
  things PLAN scoped remain unbuilt (elle migration, RocksDB state
  machine) and are stated as such in PLAN §5's note, PLAN §6.G,
  DESIGN §2.5's amendment and the README's Known limits — but a reader
  who sees only the Status table could over-read it. The Known limits
  section is the mitigation.
- **BACKLOG 11's phrasing rule is now quoted in two more places**
  (README's Findings and Known limits, `harness/README.md`'s probe
  section). If the probe rules are ever hardened, those two documents
  need updating alongside the backlog item.
- I did not verify any *run result*. RUNS.md's numbers were treated as
  given (they are reviewed evidence); I only used them as the source
  for what may be claimed elsewhere.

## What I did not change, and why

- **`jobs/**/*_brief.md`, `reviews/**/*.md`, and all previously merged
  `jobs/**/*_report.md`** — immutable historical record, per the brief.
  Several contain claims that are now out of date (Job 03's report
  describes a harness with no workloads, for instance); that is correct
  for a dated record and the refreshed `harness/README.md` is where a
  reader is now sent instead.
- **All source, config and workflow files.** The brief forbids it. One
  finding that would otherwise have been a code change is reported
  instead: the missing `LICENSE`/`NOTICE` (BACKLOG 15).
- **`docs/RUNS.md`'s run entries** — no result, verdict, count, store
  name or excerpt altered. Including the Job 07 reinterpretation note,
  which is a correction the coordinator made and which must stay exactly
  as written.
- **`docs/PLAN.md` §1, §2, §4, and the §6 leanings themselves; DESIGN's
  original body text** — historical decision records. Corrections were
  made *beside* them as dated amendments, never in them. PLAN §2.3's
  "Private for now" already carried its own 2026-08-04 update; I left
  it alone.
- **`docs/BACKLOG.md` items 1, 2, 3, 7, 8, 9, 10, 11, 12, 13** — all
  still accurate against the merged reports, including every classification.
- **`.github/workflows/fuse-spike.yml`'s existence.** It is a throwaway
  spike artifact whose own header says to delete it once M4's workflow
  lands; M4 landed without a separate workflow (the durability kinds are
  tokens in `jepsen.yml`), so the deletion condition arguably arrived.
  Deleting it is a repository change, not a documentation one — flagged
  here for the coordinator rather than acted on, and deliberately not
  mentioned in the README, which describes the `jepsen` workflow only.
- **`env/README.md`'s knobs, lazyfs and architecture sections;
  `docs/PROCESS.md`'s existing rules; `CLAUDE.md`'s hard rules** —
  verified accurate, so left alone.

## Suggestions (out of scope)

1. **Add `LICENSE` and `NOTICE`** (BACKLOG 15). One coordinator commit;
   the donation endgame makes it cheaper now than later.
2. **Delete `.github/workflows/fuse-spike.yml`** per its own header, or
   amend the header to say why it is being kept.
3. **Consider a `docs/STATUS.md`** if DESIGN's history/contract split
   keeps needing explanation — or, better, promote §2.6 and the outcome
   table to their own short contract document and let DESIGN be purely
   the M0 record.
4. **The next run of this job should be triggered by**, at minimum: any
   new nemesis kind, workload, CLI option, workflow input, or BACKLOG
   classification change. Those are the five surfaces that made this
   run's README wrong.
