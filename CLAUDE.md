# Lithos-Client: working notes for this branch (`upkeep-adapter`)

This branch adds a general chain-maintenance ("upkeep") candidate source to the Lithos client. The
design brief is `UPKEEP-BRIEF.md`; the task for each session is in `prompts/`. Read both before
touching code. This file, the brief and `prompts/` are working files and are removed before the pull
request is opened upstream.

## Build and test (what actually works)

- Scala 2.12.20, sbt 1.6.2, Play 2.8.15. **sbt 1.6.2 does not start on Java 21** (the build definition
  fails with "bad constant pool index"). Use Java 17:

  ```sh
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH
  sbt -batch compile
  sbt -batch "testOnly transactions.upkeep.*"          # unit specs for the new source
  sbt -batch "testOnly contracts.specs.upkeep.*"      # offline contract specs (no node needed)
  sbt -batch test                                      # the whole suite, slow
  ```

- A first compile downloads the compiler bridge and the Play toolchain; allow ten minutes.
- If you cannot run sbt where you are, say so in your final report and list the files you changed.
  The operator compiles locally and sends compiler output back as the next prompt.

## Conventions this repository keeps (follow them)

- **Doc comments say why, not what.** Every class and non-trivial method carries a comment stating
  the reason for its shape and the failure it prevents. Read `app/transactions/rent/StorageRentSource.scala`
  and `app/transactions/candidate/CandidatePreparation.scala` for the voice.
- **Actors own state; builds run off the mailbox.** Anything a build touches must be a value handed to
  it (see `CandidatePreparation.start`'s doc comment). Never read actor fields inside a `Future`.
- **Config keys mirror `conf/application.conf`.** Every new key gets a commented entry there, a case
  class under `app/configs/`, a `Default`, and a range check in `app/configs/ConfigValidation.scala`.
- **Off by default.** New sources and jobs ship disabled. The Lithos maintainer asked for this.
- **Tests are scalatest 3.2 (`AnyFlatSpec` + `Matchers`, or `AnyPropSpec`) with Mockito through
  scalatestplus.** Node access is mocked with `support.FakeNodeContext(mock[NodeApi], numAddresses)`.
  Contract specs extend `contracts.specs.harness.ContractSpecBase` and run offline through appkit's
  `FileMockedErgoClient` (ErgoTree v3, `_v6` fixtures).
- **ErgoScript sources live in `lithos-lib/src/main/resources/<group>/<Name>.ergo`** and are loaded by
  `lithos-lib/src/main/scala/lfsm/ScriptGenerator.scala`. Add a group rather than inlining a script.
- **No fee output in a candidate transaction.** The client's own block transactions carry no fee
  (confirmed by the Lithos lead developer; see the brief). Reuse the existing fee-less build path;
  do not invent a new one.
- **Never spend the operator's ERG** in this source. Inputs are only the protocol boxes a job advances.
- Commit per completed phase, message in the imperative, first line under 72 characters, body saying
  what and why. Do not commit `target/` or `project/target/`.
