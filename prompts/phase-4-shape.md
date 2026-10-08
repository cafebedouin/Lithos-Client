# Phase 4: the shape of the thing, now that it works

Start from branch `upkeep-adapter` at the tip (phase 3, `f87bab94` or later), and push there. Read
`CLAUDE.md`, `UPKEEP-BRIEF.md`, everything under `app/transactions/upkeep/`, `app/configs/UpkeepConfig.scala`,
`app/configs/HeartbeatConfig.scala`, `lithos-lib/src/main/scala/mutations/{Contract,Mutator,TxBuilder}.scala`,
and the specs under `test/transactions/upkeep/` and `test/contracts/specs/upkeep/`.

Phases 1 to 3 compiled and passed on the operator's machine (71 upkeep and contract specs, full suite green).
This phase makes the code smaller and more reusable before the pull request. Do the items in order; each
is independent enough to commit on its own. Do not change `DueJob.ergo`.

## 1. A base class for script jobs, so the next job is a rule and nothing else

Every keyless job we can foresee (the heartbeat; a Dexy tracker; an expiry refund) has the same shape:
boxes at one known script, due by a predicate on the box and the height, a successor that is a fixed
function of the box, possibly with data inputs. Today `HeartbeatJob` carries discovery, paging, parameter
reads, sizing, `TxBuilder`, the keyless prover and the capital entry itself. Lift all of that into an
abstract `ScriptJob` in `app/transactions/upkeep/`:

```scala
abstract class ScriptJob extends UpkeepJob {
  def contract(network: NetworkType): Contract              // compiled once per network, cached
  def due(box: InputUTXO, height: Int): Boolean
  /** The successor plan, or None when the box cannot be advanced. */
  def plan(box: InputUTXO, build: BuildContext): Option[Successor]
}
final case class Successor(outputs: Seq[UTXO], dataInputs: Seq[InputUTXO] = Seq.empty,
                           revenue: Seq[Int] = Seq.empty /* indices of outputs that are this miner's */)
```

`ScriptJob` then owns: discovery by tree through the index with the configured `boxIds` fallback (read
generically from `jobs.<name>.boxIds` for every script job, not only the heartbeat); the keyless prover;
`TxBuilder` with the preHeader at the block height and fee 0; the `CapitalEntry`s for `revenue`. The
box-cannot-pay check (successor under the consensus minimum) stays the job's, in `plan`, since only the job
knows its outputs. `HeartbeatJob` becomes its three methods plus `Beat`. Keep `UpkeepJob` as the trait the
source speaks to, so a job that does not fit this shape can still implement it directly.

## 2. A `BuildContext` instead of a growing argument list

`UpkeepJob.build(ctx, box, height, payTo)` becomes `build(box, bc: BuildContext)` where `BuildContext`
carries the appkit context, the node parameters read once per block build, the block height, `payTo`,
and the network. Jobs stop calling `getParameters` per box, and a later field is not a signature change
across every job.

## 3. Per-job configuration without a hard-wired case class per job

`UpkeepConfig.heartbeat: HeartbeatConfig` special-cases one job. Replace it with the registry holding
factories: `UpkeepRegistry.all: Seq[JobFactory]` where a factory is a name plus a function from that
job's config block (`stratum.candidate.sources.upkeep.jobs.<name>`, as a Play `Configuration`) to an
`UpkeepJob`. The generic keys every job may carry, `enabled` and `boxIds`, are read and validated by the
framework; a job's own keys are its factory's. `HeartbeatConfig` goes away unless the heartbeat needs a
key of its own (it does not today). Validation of `boxIds` (64 hex characters, distinct, at most
`maxBoxesPerJob`) applies to every job that lists them.

## 4. An observe mode, for a miner that has no blocks yet

Lithos has found no mainnet block so far, so there is no way to watch upkeep do anything real. Add
`stratum.candidate.sources.upkeep.mode = "candidate" | "observe"` (default `candidate`). In observe
mode the source scans, revalidates, builds and sizes exactly as it would, then puts each built successor
through the node's `checkTransaction` and logs the verdict and what it would have offered, and answers
every request empty. One node call per built successor, on the build thread, never on the mailbox. The
operator uses it to soak the heartbeat on mainnet against a real due-job box before any block carries it.

## 5. Small cleanups found reading phase 3

- `Upkeep.floor` passes `assets = 2 * box.tokens.size` into `accountedCost`, which multiplies by 2
  again: the token term is counted four times. Count it once in `floor` and say in `accountedCost`'s
  comment exactly what the factor stands for, or drop the doubling.
- `Upkeep.fitting` is no longer used by the source (the build threads `Share` through); delete it and
  port its specs to `Share`.
- `Upkeep.InitCost = 10000L` restates a node constant; name where it comes from (the rent code uses the
  same literal; if the node exposes it through `BlockchainParameters` or a `Constants` object, read it
  from there instead).
- `UpkeepRegistry.byName` is unused; delete it.
- `UpkeepSource.Memory` takes `retryAfterScans` in its constructor and `UpkeepSource` also receives it in
  `upkeepConfig`; make one the source of truth.
- The `Beat.of(NodeBox)` overload wraps `registerValues` in `Try`; after item 1 the base class parses
  registers through `toInputUTXO` once, so the `NodeBox` overload may go.

## 6. Keep everything green

Every existing spec keeps passing or is moved, not deleted: a spec that tested something now in
`ScriptJob` moves to `ScriptJobSpec` and is exercised through a minimal fake script job in the test tree,
and `HeartbeatJobSpec` keeps only what is the heartbeat's own. Add specs for: a script job's discovery on
indexed and plain nodes (moved from the heartbeat spec); a `Successor` with a data input signing and
carrying it; observe mode answering empty and calling `checkTransaction` once per built successor; a
factory reading its own key; `floor`'s token term counted once.

Run `sbt -batch "testOnly contracts.specs.upkeep.* transactions.upkeep.*"` with Java 17 if you can.
Commit per item with messages in the imperative. Update `prompts/PR-DESCRIPTION.md` for what changed
(observe mode is worth a line under "Testing"; `ScriptJob` under "What"). Report what you changed, what
you could not test, and anything you judged not worth doing and why.
