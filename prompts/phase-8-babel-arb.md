# Phase 8: the first arbitrage job, a Babel box against an ErgoDEX v1 pool (positive control)

Create branch `upkeep-babel-arb` from `upkeep-source` (the head of PR #15, 63a1f03e) and push to it. Do not base it
on `upkeep-space-option`. This is not a pull request yet: the maintainer is reviewing #14 to #16 and has said
arbitrage comes first, on the upkeep source as its base. The job is the positive control that every later arbitrage
idea must beat, so keep it small, exact and boring.

Read `CLAUDE.md`, then `app/transactions/upkeep/UpkeepJob.scala`, `ScriptJob.scala`, `UpkeepSource.scala` (the
read-back and the refusals around lines 370-410), `UpkeepRegistry.scala`, `jobs/HeartbeatJob.scala`,
`app/configs/UpkeepConfig.scala`, and the ErgoDEX adapter: `app/transactions/batching/ergodex/ErgoDexContracts.scala`
(`NativePoolErgoTree`, `NativePoolTokens`, `FeeDenominator`) and `ErgoDexPool.scala` (`native`, `outputAmount`).
Tests to copy the style of: `test/transactions/upkeep/HeartbeatJobSpec.scala`, `UpkeepSourceSpec.scala`, and
`test/contracts/specs/upkeep/DueJobSpec.scala` for evaluating a script in the harness.

## The trade

An EIP-31 Babel box (template below) is a standing bid: it pays ERG for one token at the price in R5 (nanoERG per
token unit) to whoever recreates it at the output index given in its context variable 0 (`Int`), with the same R4
(creator `SigmaProp`) and R5, R6 = the spent box's id, the token at `tokens(0)`, and
`(tokensAfter - tokensBefore) * R5 >= ergBefore - ergAfter >= 0`. When an ErgoDEX v1 ERG pool sells that token for
less than the bid, one transaction closes the gap with no capital:

    inputs:  pool (0), Babel box (1)
    outputs: pool successor (0)   +X ERG, -T tokens       (Pool.sc swap rule, fee from R4 over 1000)
             Babel successor (1)  -Y ERG, +T tokens       (Y <= T * bid; context var 0 on input 1 = 1)
             revenue (2)          Y - X at bc.payTo        (no fee output; no wallet input)

Template tree, `{tokenId}` substituted:
`100604000e20{tokenId}0400040005000500d803d601e30004d602e4c6a70408d603e4c6a7050595e67201d804d604b2a5e4720100d605b2db63087204730000d606db6308a7d60799c1a7c17204d1968302019683050193c27204c2a7938c720501730193e4c672040408720293e4c672040505720393e4c67204060ec5a796830201929c998c7205029591b1720673028cb272067303000273047203720792720773057202`.
Source: https://github.com/ergoplatform/eips/blob/master/eip-0031.md. Pin the tree for each configured token the way
the client pins other trees, and add a spec that compiles the EIP's ErgoScript and compares.

## 1. The arithmetic, alone, with property specs

`BabelArb.optimal(pool, bid, babelErg, minBoxValue): Option[Plan]` with `Plan(x, t, y, profit)`. Closed form for the
CPMM with fee (marginal price after the swap equals the bid), then an integer search around it so that the
pool's swap rule and the Babel rule both hold exactly on integers, rounding against us. Y is bounded by the Babel
box's ERG less a minimum box value. Return nothing below a configured `minProfit` (nanoERG). Specs: no gap gives
nothing; a gap gives profit > 0 and both rules hold; the result is maximal within one unit of X; Y never leaves the
Babel box below the minimum; large reserves do not overflow (BigInt where the pool contract uses it).

## 2. The job

`jobs/BabelArbJob.scala`, a direct `UpkeepJob` (two inputs and a computed size do not fit `ScriptJob`). Config under
`stratum.candidate.sources.upkeep.jobs.babel-arb`: `enabled = false`, `tokens = []` (token ids), `pools = []`
(optional pool NFTs; empty means discover every v1 ERG pool for the token by tree), `minProfit`. Discovery returns
the Babel boxes for the configured tokens and the pool boxes they trade against, because the source refuses any
input its job did not report and the build did not read back. Build from the Babel box: read the pool fresh, plan,
sign, declare the revenue output as capital. At most one arbitrage per pool per block (the pool is spent once); pick
the most profitable Babel box for it. A pool box passed to `due` is never due by itself.

The framework will not fit exactly: `due` is meant to be pure in the box and the height, `build` takes one box, and
the source's output checks are written for `ScriptJob`. Change the framework only where you must, in a separate
commit, and write each mismatch and what you did about it into `prompts/BABEL-ARB-NOTES.md`. That note is the
feedback the maintainer asked for ("a protocol job is what proves it is worth having, and it would shape
ScriptJob"), so it matters as much as the code. Respect the maintainer's rule: no round trip between the candidate
builder and the sources; anything new is a one-way message or a cache the source reads.

Add a spec in the style of the heartbeat's asserting that every output of a built transaction is the pool's
script, the Babel box's script or `bc.payTo`; that no input is at the wallet's keys; that there is no fee output; and
that a Babel box whose bid is under the pool price is never built.

## 3. Script evaluation

In the contract harness, evaluate a built transaction against the real pool tree and the real Babel tree and show
both pass; then show that a Babel successor with one token too few, and a pool successor one unit too generous, are
each refused.

Run `sbt -batch "testOnly transactions.upkeep.* contracts.specs.upkeep.*"` with Java 17 if you can; the sbt in this
repository breaks on Java 21. One commit per section. Report per section, and list in the notes file anything a devnet
run must check that the specs cannot (the next step is a peeryard devnet hook that creates the pool and the Babel box
and waits for this job's transaction in a block).
