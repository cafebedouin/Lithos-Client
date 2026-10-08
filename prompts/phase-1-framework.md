# Phase 1: the upkeep framework (no protocol job yet)

Read `CLAUDE.md` and `UPKEEP-BRIEF.md` at the repository root first, then the files the brief names,
in this order: `app/transactions/rent/StorageRentSource.scala`, `app/transactions/rent/StorageRent.scala`,
`app/transactions/candidate/CandidatePreparation.scala`, `BlockTxMessages.scala`, `CandidateBundle.scala`,
`app/configs/CandidateSourceConfig.scala`, `CandidateConfig.scala`, `RentConfig.scala`,
`ConfigValidation.scala` (the sources loop near line 175), `app/tasks/StartMiningServer.scala:90-140`,
`test/transactions/rent/StorageRentSourceSpec.scala`, `test/support/FakeNodeContext.scala`,
`lithos-lib/src/main/scala/mutations/{Mutator,Contract,TxBuilder}.scala`.

Then build phase 1 of the brief (section 5): the `upkeep` candidate source, the `UpkeepJob` trait,
the registry, the pure fitting helpers, config (`UpkeepConfig`, defaults, validation, the
`application.conf` block, **off by default**), wiring in `StartMiningServer`, and specs that drive the
source with a `FakeJob` defined in the test tree. Do not write the heartbeat job or any ErgoScript in
this phase.

Specific requirements:
- `UpkeepSource` mirrors `StorageRentSource`'s split: a timer scan that only remembers box ids per job,
  and a candidate path that re-reads the boxes, filters by `due`, builds, fits to `limits.budget`, and
  answers through `CandidatePreparation`. Nothing on the mining path reads the actor's fields.
- The scan asks each enabled job for `discover`; a job that throws is logged and skipped, never
  taking the source down. A restarting actor must not re-offer what the node refused in the same run.
- `UpkeepJob.build` returns an already-signed appkit `SignedTransaction` plus its `CapitalEntry`s;
  the framework turns it into a `CandidateTx` the way `StorageRent.member` does, and checks that the
  signed inputs are a subset of the boxes the job discovered (rule 1 of the brief).
- Add `CandidateSourceConfig.Upkeep = "upkeep"`, a `Default` entry with `enabled = false`, and the
  name in the `ConfigValidation` loop. Per-job enable flags live under
  `stratum.candidate.sources.upkeep.jobs.<name>.enabled`, read generically so a new job needs no
  config code.
- Specs (scalatest, mocked `NodeApi` through `support.FakeNodeContext`): a disabled source answers
  empty with no node read; an enabled source with no enabled job answers empty; the scan stores what
  `discover` returned and the build drops ids the node no longer returns; a not-due box is not built;
  a job spending an undiscovered box is rejected; fitting stops at `maxTxs`, `maxBytes`, `maxCost`;
  `CandidateTxsDropped` forgets a prepared height; a refused build is not retried next block.
- Every new class and non-trivial method carries a doc comment in the repository's voice (why, and
  what failure the shape prevents).

Run `sbt -batch compile` and `sbt -batch "testOnly transactions.upkeep.*"` with Java 17 as
`CLAUDE.md` says, if the environment allows; iterate until green. If you cannot run sbt, say so.
Commit as one commit, "Add the upkeep candidate source: a registry of keyless maintenance jobs",
with a body naming the files and what is still mocked. End your report with: the files added and
changed, what was tested and how, and every place you were unsure of an API and guessed.
