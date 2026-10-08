# Upkeep: a general chain-maintenance candidate source for the Lithos client

Working brief for the `upkeep-adapter` branch. Written 2026-10-08 from code inspection of
`Lithos-Client` master (`da4a4666`) and the skunkyard research line on agents
(`research/agents/ROADMAP.md` R1, `UPKEEP.md`). Everything stated about this repository below was
read in the files named; anything about Dexy or other protocols is secondhand and marked so.

## 1. Goal

A pull request the Lithos maintainers can merge that adds **one new candidate source, `upkeep`,**
which performs *keyless, fee-less maintenance transactions* inside the miner's own block candidates,
drawn from a **registry of jobs**, each job being a reviewed, protocol-specific description of
(a) which boxes it maintains, (b) when one is due, and (c) what its successor is.

The Lithos lead developer agreed in principle (private reply, 2026-10-05): executors as configuration
options, **disabled by default**, "revenue or not"; the client's own block transactions carry no fee;
"Lithos miners are in a unique place to be able to do that sort of job"; a PR is welcome. He plans a
Dexy integration himself once Dexy's contracts are relaunched, so **the Dexy job is not in this PR.**
What is: the framework, its first reference job, tests, config and docs, written so that a Dexy job
(or any other) is one file plus one config block.

Storage rent (`app/transactions/rent/`) is the existing keyless source and the model: it finds due
boxes on a timer, revalidates by re-reading them, assembles transactions nothing signs, fits them to a
budget, and offers them as `CandidateBundle`s. Upkeep generalizes exactly that shape over jobs whose
"due" is a script condition rather than the four-year rent rule.

## 2. How a candidate source works in this client (read, not assumed)

- **Protocol.** A source is an Akka actor the stratum asks twice per block height
  (`app/mining/CandidateBuilder.scala:460,488`): `PrepareBlockTxs(height, limit)` as soon as the
  height is known, then `RequestBlockTxs(height, limit, refresh)` when the package is collected;
  it answers `BlockTxsReady(height, Seq[CandidateBundle])`, and is told `CandidateTxsDropped(height)`
  when a height can no longer land. Messages: `app/transactions/candidate/BlockTxMessages.scala`.
- **Helper.** `transactions.candidate.CandidatePreparation` (same directory) owns the prepared /
  building / waiting state so the build runs off the mailbox; `StorageRentSource` shows the usage
  (`startBuild`, `preparation.receive` chained into `receive`, `preparation.drop`).
- **Bundle.** `CandidateBundle(members: Vector[CandidateTx], interactions, capital: Seq[CapitalEntry])`
  (`CandidateBundle.scala`). `CandidateTx(id, json, kind, inputIds, sizeBytes, cost, leaf)`; the
  rent source builds members through `StorageRent.member` using `RollupExecution.signedInputIds /
  signedSizeBytes / signedLeaf` and `CandidateTx.signedJson`. `CapitalEntry` names revenue outputs a
  final top-up may aggregate; `CandidateCapital.collectionContract(wallet, useTrueProp)` is the
  script those outputs use (P2PK of the node wallet, or TrueProp when the operator opts in).
- **Budget.** Per-source limits `CandidateSourceConfig(enabled, maxTxs, maxBytes, maxCost)` under
  `stratum.candidate.sources.<name>` (`app/configs/CandidateSourceConfig.scala`); names are
  constants in that object; defaults in `CandidateConfig.Default.sources`
  (`app/configs/CandidateConfig.scala:43`); range checks loop over the names in
  `app/configs/ConfigValidation.scala:175`; per-source extras follow `app/configs/RentConfig.scala`.
  `StorageRent.fitting` shows how a source bounds itself to `limits.budget` before the package does.
- **Wiring.** `app/tasks/StartMiningServer.scala:97-133`: `limitsFor(name)`, construct the actor
  only when enabled, add a `mining.MiningMessages.CandidateSource(name, ref)` to the list.
- **Fee-less build.** `StorageRent.merged` (`StorageRent.scala:527`) builds with
  `TxBuilder(ctx).setInputs(..).setOutputs(..).buildTx(0L, wallet.p2pk)`; appkit then signs. A
  transaction whose inputs' scripts reduce to true needs no secret: `ctx.newProverBuilder().build()`
  (check that this is how the client already signs TrueProp-guarded inputs before relying on it;
  `ConsolidationExecution` / `CandidateCapital` are the places to look).
- **Node reads.** `lithos-lib/src/main/scala/node/NodeApi.scala`: `boxesWithPoolByIds`,
  `boxById`, `unconfirmedOutputsByErgoTree`, `unconfirmedOutputsByTokenId`, the scan API
  (`registerScan`, `scanUnspentBoxes`), and a `NodeIndexerApi` trait (read it: it is the route to
  "unspent boxes by ErgoTree / by token" on a node with `extraIndex`). Discovery must work on a
  node *without* the extra index too, or say clearly in config that it needs one.
- **Mutations idiom.** `lithos-lib/src/main/scala/mutations/`: `Contract(ergoTree, mutators)`,
  `Mutator { preReqs: Seq[TxContext => Boolean]; mutation(tCtx): Seq[UTXO] }`, `TxBuilder.mutateOutputs`.
  A job's "due" condition is a `preReq`; its successor is the `mutation`. Use it where it fits;
  do not force it where a plain function is clearer (the rent code does not use it for the sweep).

## 3. Design to build

```
app/transactions/upkeep/
  UpkeepSource.scala      actor: timer scan over enabled jobs; candidate path = revalidate + build + fit
  UpkeepJob.scala         trait: name; discover(ctx, api, height): Seq[DueBox]; due(box, height): Boolean;
                          build(ctx, box, height, payTo): Option[Built]   (Built = signed tx + capital + cost/bytes)
  UpkeepRegistry.scala    the jobs this client knows, by name; which are enabled comes from config
  Upkeep.scala            pure helpers: fitting to a budget, estimated cost/bytes, member() construction
  jobs/HeartbeatJob.scala the reference job (section 4)
app/configs/UpkeepConfig.scala        stratum.candidate.sources.upkeep.{scanIntervalMs, jobs.<name>.enabled, ...}
lithos-lib/src/main/resources/upkeep/DueJob.ergo   the reference contract
test/transactions/upkeep/*Spec.scala  source and job specs (mocked NodeApi via support.FakeNodeContext)
test/contracts/specs/upkeep/*Spec.scala  the contract run offline through ContractSpecBase
conf/application.conf                 the documented config block (off by default)
```

Rules every job must satisfy, enforced by the framework where possible and stated in `UpkeepJob`'s
doc comment otherwise:

1. **No wallet input, no fee output.** Inputs are only the boxes the job maintains (plus data inputs).
   The framework rejects a built transaction that spends a box the job did not discover.
2. **Deterministic successor.** Given the box and the height, the output set is fixed. No search,
   no pricing, no market reads. Jobs that need search (arbitrage, order solving) are not upkeep.
3. **Revenue goes to `payTo`** (the collection contract the framework hands in), declared as
   `CapitalEntry` so the top-up can aggregate it. A job may have no revenue.
4. **Revalidate before build.** Boxes are kept as ids between scans; the build re-reads them
   (`boxesWithPoolByIds`) and forgets the ones that do not come back, as the rent source does.
5. **Refusals are remembered.** A transaction the node refuses (when checked) is not rebuilt every
   block; the box is dropped until the next scan finds it changed.
6. **Bounded.** Each job's work is fitted to the source's `maxTxs/maxBytes/maxCost`, and the source
   sits under the package share like every other.
7. **Never extractive.** Nothing here reorders, front-runs or sandwiches anyone's transaction. The
   doc comment on the source says so, and the PR description repeats it.

Broadcast mode (sending upkeep to the mempool with a fee from the operator's wallet when this miner
finds no block) is **out of scope** for this PR: it spends operator ERG, which the client's own rule
forbids. Note it as a follow-up in the PR text.

## 4. The reference job: a self-describing due-job box ("heartbeat")

This is the `U3` question from the research line: can a box fully determine its own successor so that
any miner executes it with no protocol knowledge? The contract is deliberately minimal.

`DueJob.ergo` (ErgoTree v3; constants as the client's other contracts take them):

- Registers: `R4: Int` last-beat height, `R5: Int` period in blocks, `R6: Long` tip per beat in nanoERG.
- Spendable by anyone when `HEIGHT >= SELF.R4 + SELF.R5`.
- `OUTPUTS(0)` is the successor: same `propositionBytes`, same tokens, `R4 == HEIGHT`, `R5` and `R6`
  unchanged, `value >= SELF.value - SELF.R6`.
- The remainder (at most `R6`) is unconstrained: the executor pays it where it likes (the framework
  pays it to `payTo`).
- When `SELF.value - R6` would fall under the minimum box value the box simply cannot be advanced;
  it then ages out through storage rent. State this in the contract header; do not add an owner path
  (keep the first version keyless end to end).

`HeartbeatJob` discovers boxes at that ErgoTree (by tree on an indexed node, with a configured list of
box ids or token ids as the fallback for an unindexed node, whichever the node API makes cheap; read
`NodeIndexerApi` and decide), treats one as due by the register rule, and builds the successor plus
the tip output. Its contract spec proves, through the interpreter, that: a due box advances; a box
one block early is refused; a successor with the wrong R4, a changed R5/R6, a missing token or too
small a value is refused; a tip larger than R6 is refused.

## 5. Phases (one cloud session each; the operator compiles and tests between them)

- **Phase 1, framework.** Everything in section 3 except the heartbeat job and contract, with a
  `FakeJob` in tests driving the source: discovery on a timer, due filtering, revalidation, fitting,
  refusal memory, `PrepareBlockTxs`/`RequestBlockTxs`/`CandidateTxsDropped` behaviour, `enabled=false`
  answering empty without a node read. Config, validation, defaults, wiring, `application.conf` text.
- **Phase 2, heartbeat.** `DueJob.ergo`, its contract spec, `HeartbeatJob`, its spec, registered.
- **Phase 3, hardening and PR shape.** Cost estimation against the node's parameters as the rent code
  does it; logging that names the job; `logBudgets` participation; a README section; squash to a
  reviewable series; PR description (what, why, off by default, not extractive, broadcast as
  follow-up, Dexy as the next job).
- **Later, outside this PR:** a Dexy job against the relaunched contracts, coordinated with the
  Lithos developer; a testnet deployment of a due-job box and a Lithos testnet block carrying its beat.

## 6. Acceptance for the PR

- `sbt compile` and `sbt test` pass on Java 17 (the operator runs them).
- With the default config nothing changes: `upkeep.enabled = false`, no actor started, no node reads.
- With `upkeep.enabled = true` and no job enabled: the source answers empty.
- A due-job box on a devnet is advanced in a Lithos candidate (operator's check, after phase 2).
- Diff touches existing files only to wire and configure; no behaviour change to other sources.
