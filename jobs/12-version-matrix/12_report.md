# Job 12 report — M5: version matrix and mixed-version topology

*Status: in progress — this commit exists to put the mixed-version
expectations on record BEFORE those runs execute (brief deliverable 4:
"Expectations stated in advance in your report"). The 3.3.0 full-suite
sweep is running as this is written; every other section lands with the
final commit.*

## Mixed-version expectations (stated in advance, 2026-08-07)

The pair under test is **3.2.2 (old) / 3.3.0 RC2 (new)**; the harness
client runs the OLD version in all three runs (rolling-upgrade
convention: clients upgrade last). Static mixed runs split the voters
n1–n3 = old, n4–n5 = new (majority-old; elections may seat either
version, so both old-leader→new-follower and new-leader→old-follower
append directions occur). The rolling run starts all-old and rolls
n1…n5 to new, one at a time, while the register workload runs.

1. **register + partition, static mixed — expect GREEN** (linearizable,
   live, `:info` only inside fault windows). Raft wire compatibility
   within a minor release line is an explicit upstream norm; the 3.3.0
   sources show no appendEntries/requestVote/installSnapshot proto field
   removals. What a failure would look like if the expectation is wrong:
   cross-version append/vote rejections → mass `:info`, election storms,
   liveness red; semantic drift → a linearizability conviction.
2. **register + crash, static mixed — expect GREEN.** Crash-restart
   additionally exercises RECOVER of each version's own storage plus
   cross-version log catch-up after restarts. Same failure signatures as
   above.
3. **rolling upgrade — expect GREEN and complete**: all five `:roll` ops
   return `:await :started` (the new version opens the old version's
   raft storage in place — storage format forward-compatibility within
   the minor line), the rolling-evidence checker reports 5/5 rolled,
   and linearizability + liveness hold through every intermediate mix
   from 5-old to 5-new. After the last roll the 3.2.2 client runs
   against an all-3.3.0 cluster for the run's tail — client-compat in
   the clients-upgrade-last direction. A red here would name the exact
   mix that broke (the roll ops record node/from/to/versions-now).

We additionally expect the mid-roll cluster to serve during each roll
(one voter down at a time is a minority throughout; elections when the
leader is rolled should complete inside the liveness checker's 60 s
window).

*(Remaining sections — Summary, What was built, How it was verified,
Deviations, Known gaps, Suggestions — follow with the final commit.)*
