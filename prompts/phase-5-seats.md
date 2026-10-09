# Phase 5: the review round

Start from branch `upkeep-adapter` at the tip (`ea7438c8` or later) and push there. Read `CLAUDE.md`, then everything
under `app/transactions/upkeep/`, `app/configs/UpkeepConfig.scala`, `app/configs/ConfigValidation.scala` (the upkeep
parts), `lithos-lib/src/main/resources/upkeep/DueJob.ergo`, `test/contracts/specs/upkeep/DueJobSpec.scala`, the specs
under `test/transactions/upkeep/`, `conf/application.conf` (the `upkeep` block), `README.md` (the Upkeep section) and
`prompts/PR-DESCRIPTION.md`. For the two ErgoScript idioms below read `lithos-lib/src/main/resources/collateral/Collateral_Mainnet.ergo`
lines 110-125 and 205-215.

Five independent reviewers read the PR as the maintainer would. Every point below was checked against the Ergo node
source before it was accepted; do them all, in this order, one commit per numbered section. Phases 1 to 4 compiled and
passed on the operator's machine (full suite 2,698 tests; the only failures are a known load-sensitive spec,
`state.persistence.SnapshotFallbackSpec`, unrelated to this branch).

## 1. The contract (highest priority; do not change anything else in this commit)

`DueJob.ergo` lets two due boxes with the same tokens, R5 and R6 be spent in one transaction against a single
`OUTPUTS(0)`, and the spender keeps the second box whole. Fix the script:

- `val onlyOne = INPUTS(0).id == SELF.id` in the conjunction (the idiom at `Collateral_Mainnet.ergo:210`).
- `val freshStamp = successor.creationInfo._1 == HEIGHT` (idiom at `Collateral_Mainnet.ergo:118`), so a beat resets the
  storage-rent clock.
- `val due = HEIGHT.toLong >= lastBeat.toLong + period.toLong` (no Int overflow), and `val sane = period > 0 && tip >= 0L`.
- `val valueKept = successor.value >= SELF.value - min(tip, SELF.value)`.
- Header: remove the sentence that reads like a research question; state the rule in words including the four new
  conditions; rewrite "HOW THE BOX ENDS" to say that `>=` allows a smaller or free beat, so a box lives as long as anyone
  beats it and ages out through storage rent only when left alone; add that R4 + R5 is computed in Long; that wrong
  register types lock the box until rent; that tokens stay with the box; that a tip above the value means the creator
  offered the whole box.

`DueJobSpec`: add the two-input merge as a refused property (same script, no tokens, same R5 and R6, one successor worth
`max(value) - tip`, the remainder to the spender), a successor keeping the input's creation height refused, a
`period = 0` box refused, `tip > value` advancing with the whole value takeable, and `lastBeat + period` over `Int.MaxValue`
still due. Keep the differential style.

## 2. The heartbeat job

- Pay `min(tip, value - successorFloor)`; when that is below the tip output's own minimum, beat for free (one output).
  `plan` returns `None` only for a box whose registers are not a beat. Update `HeartbeatJobSpec`.
- Pin the tree: `HeartbeatJob.TreeHex` as a constant (the ErgoTree takes no constants, so it is the same on every network;
  confirm by compiling for MAINNET and TESTNET in a spec and asserting both equal the constant). Discovery compares against
  the constant.
- Doc: the read-back is the node's mempool-adjusted view, so a box a pending transaction already spends is skipped for that
  block (replace the paragraph that says a mempool beat does not hold a box back).

## 3. `ScriptJob`

- `def priority(box: InputUTXO): Long = 0L`; the heartbeat returns the due height. `discover` sorts by priority before
  the source's cap applies, and returns configured ids first, outside the cap (the source must honour that: configured ids
  are never cut).
- Configured `boxIds` are read with `api.boxById` (UTXO set only), one call each, so an unconfirmed box is never spent
  without its parent. Say in the doc that `boxIds` is read on every node, indexed or not, and is not a fallback.
- Skip boxes `transactions.rent.StorageRent.blockedByReEmission(box, network)` names, as rent does.
- `signed` requires the plan to balance exactly (`outputs.map(_.value).sum == box.value`) with a message naming the job;
  doc says why (`TxBuilder` refuses sub-minimum change without a fee output).
- Doc on `ScriptJob` and `UpkeepJob`: the preHeader at signing carries only the height; a script reading minerPk, votes or
  timestamp would sign here and be refused by the node.

## 4. The source

- `verifyWithNode = true` (new key, validated, documented): in candidate mode, after a build admits its successors, put each
  through `nodeApi.checkTransaction` and refuse (remember) any the node refuses. This runs in the build triggered by
  `PrepareBlockTxs`, which is off the request path; the node evaluates the check at the next block's height, the same as
  the candidate. Spec it with the mocked api.
- Observe mode: answer every request empty at once, and run the build and checks as a detached task on the worker
  (`Future` on `candidateWorker`, never awaited). Correct the `application.conf` comment.
- Stop a build after 16 refusals (`Upkeep.MaxRefusedPerBuild`, doc why: a job that throws after real work must not cost a
  build hundreds of signings). Spec.
- Read boxes back in chunks of 256 ids.
- Rotate the start of the box order by `blockHeight % n` so a deferred head does not starve the tail.
- First scan tick after 2 s, then every `scanIntervalMs`.
- Split "cannot pay" from transient refusal: a job returning `None` from `build` is `Exhausted`, logged once at info and not
  retried until a scan stops finding the box; a throw or an undiscovered input stays a `Refused` with the retry rule.
- Periodic "scan holds" line at debug; info only when the counts change.
- Create no actor when no job is enabled (`StartMiningServer`), and drop the `active` gate if nothing else needs it.
- `CandidateTx.Upkeep = "upkeep"` beside the other kind constants, used by `Upkeep.kind`; widen `CapitalOrigin.ExecutorReward`'s
  doc to cover upkeep tips, or add an origin if the enum is used for revenue reporting (read `CapitalEntry.scala` and decide).
- Move the upkeep validation into `UpkeepConfig.validate(v)` following `ConfigValidation.scala:77-78`.

## 5. Text

- Remove every process leak: "as the Lithos maintainers asked" (`CandidateConfig.scala`, `UpkeepSpec`), "the operator
  soaks…", "(to be confirmed by the operator…)", the maintainer in the third person. Rename the unknown-job name in specs
  from `dexy` to `nosuchjob`.
- Cut the doc comments by about half: `UpkeepJob`'s seven rules become four sentences; "never extractive" is said once, in
  `UpkeepSource`.
- Fidelity's wording fixes: "one implementation, one entry in `UpkeepRegistry.all`, and one config block"; "a job name
  that is enabled and unknown is refused at startup"; drop "exactly" (README, PR); drop "in the order the scripts allow";
  `Upkeep.undiscoveredInputs` doc says the check is against what the job reported and that `ScriptJob` is what keeps the
  wallet out.
- README: the generic "Block Transactions" paragraph becomes one sentence; the Upkeep subsection says the `boxIds` list
  goes stale after each beat on a plain node, and that observe mode makes up to `maxTxs` node checks per block.
- `PR-DESCRIPTION.md`: "Not extractive" rewritten as: nothing reads pending transactions, so nothing reorders or
  front-runs; a job's transaction may only spend boxes it discovered; the shipped job finds boxes by script and signs with
  no key and no fee; the read-back is the mempool-adjusted view, so a box a pending transaction spends is skipped. Add a
  "Limits" section: the package-wide loss when the node rejects inserted transactions is a client-wide gap this PR narrows
  with `verifyWithNode` but does not close; discovery keeps the soonest-due boxes up to the cap, so a flood of cheap due
  boxes can still crowd a job; refresh rebuilds as the rent source does. Replace the testing line with the real run: Java
  17, `sbt test`, 2,698 tests, the 8 known-flaky snapshot cases named. Say the heartbeat is a reference job with its tree
  pinned and that no due-job box exists on mainnet yet.

Run `sbt -batch "testOnly contracts.specs.upkeep.* transactions.upkeep.*"` with Java 17 if you can. Report per section:
what changed, what you could not test, and anything you judged wrong in this list with the file:line that shows it.
