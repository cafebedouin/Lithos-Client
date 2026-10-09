# Phase 7: the follow-on PR, upkeep that fills space blocks would leave empty

Create branch `upkeep-space` from the tip of `upkeep-adapter` and push to `upkeep-space`. This is a second, separate
pull request stacked on the first, so the maintainer can merge or close it on its own: touch nothing the first PR does
not need changed, and keep every default so that behaviour with an unchanged config is identical.

Read `CLAUDE.md`, `app/transactions/upkeep/Upkeep.scala` (the `Share` and `floor`), `UpkeepSource.scala` (the build
loop in `advance`), `app/configs/UpkeepConfig.scala`, `app/transactions/candidate/CandidateBundle.scala`
(`CandidateBudget`), `app/mining/CandidateBuilder.scala` (how the package share is derived from the node's limits,
`blockShare`), and `lithos-lib/src/main/scala/node/NodeApi.scala` (`poolHistogram`, `unconfirmedTransactions`).

## Why

Ergo blocks are mostly empty, and a fee-less upkeep transaction almost never displaces a paying one. Today upkeep
takes a fixed per-source share in a fixed order. Two things make it take the best-paying maintenance first and use
only space that would otherwise go unused.

## 1. Order due work by value per byte

Before admission, `advance` orders the due boxes across jobs by what the block earns for the space: tip per byte, then
cost, descending; ties by the rotation already in place. The tip is what the job declares as revenue (the capital
entries); a job with no revenue sorts last. Sizing a box before it is signed already exists (`Upkeep.floor`); the
tip is known only after the plan, so sort what has been built so far at admission time, or add `def expectedRevenue(box:
InputUTXO): Long = 0L` to `UpkeepJob` (the heartbeat returns its R6 tip) and sort before building. Choose the second:
it lets the loop build the best first and stop when the share is full. Spec: three due boxes with different tips and
a share of two slots admit the two highest tips per byte.

## 2. An opportunistic share

`stratum.candidate.sources.upkeep.space = "fixed" | "opportunistic"` (default `fixed`, today's behaviour). In
opportunistic mode the source's share for a block is the larger of its configured share and what the package share
would leave empty after the mempool's fee-paying demand: read the mempool once per build (`poolHistogram` if it
gives bytes and cost per fee band, otherwise `unconfirmedTransactions` sizes and costs), subtract that demand from
the package budget the candidate builder works with (`CandidateBudget.of(maxBlockSize, maxBlockCost, blockShare)`),
and let upkeep grow into the remainder, never above it, and never above an absolute cap
`opportunisticMaxTxs` (default 20) so a runaway job cannot fill a block. When the mempool is full the share falls
back to the configured one. Document in `application.conf` that this is block space nobody paid for; and in the
PR text that the policy question, whether fee-less work should take space paying work would have taken later in the
block, is the maintainer's.

Specs: demand above the budget leaves the configured share; an empty mempool lifts the share to the remainder, capped;
a node read failure leaves the configured share; the fixed mode never reads the mempool.

## 3. Text

`prompts/PR-DESCRIPTION-space.md`: what, why, that it is stacked on the upkeep PR and independent of it, the default
being unchanged, the policy question stated plainly, how it was tested. Short.

Run `sbt -batch "testOnly transactions.upkeep.*"` with Java 17 if you can. One commit per section. Report per section.
