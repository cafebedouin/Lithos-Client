# Add an upkeep candidate source: keyless maintenance of other protocols' boxes in the miner's block

## What

A new transaction source, `upkeep`, that advances boxes other protocols leave for anyone to maintain,
inside this miner's own block candidate, with no key and no fee (enforced for every job by the source's input and
output rules, and by `ScriptJob`'s keyless prover). It is a registry of jobs: each job is a
reviewed description of one protocol's boxes — which ones it maintains, when one is due, and what its
successor is — and the source around it owns everything else: a discovery timer, revalidation by reading
the boxes back before a block is built, sizing and fitting to the source's share, a memory of refused and
exhausted boxes, an optional node check of every successor, and the prepare/request/drop protocol every
candidate source speaks.

A job whose boxes sit at one known script and whose successor is a fixed function of the box extends
`ScriptJob`, and is then its rule and nothing else: `contract`, `due`, `priority`, and `plan` returning a
`Successor` (outputs, data inputs, which outputs are revenue). `ScriptJob` owns discovery by script through
the node's index, soonest due first, plus the configured `boxIds`, read from the UTXO set on every node;
assembly with no fee and no wallet input, the outputs spending the box to the nanoERG; signing with a prover
that holds no key; and the capital entries. A new protocol is one implementation, one entry in
`UpkeepRegistry.all`, and one config block; a job that does not fit the script shape implements `UpkeepJob`
directly.

The first job, `heartbeat`, is a reference job. It advances a minimal self-describing due-job box
(`upkeep/DueJob.ergo`: R4 last beat, R5 period, R6 tip; spendable by anyone once due, one box per
transaction; the successor keeps the script, tokens and terms, is stamped with the block's height and at
most the tip leaves). Its tree is pinned in `HeartbeatJob.TreeHex`, and a spec holds the compiled script to
it on mainnet and testnet. It pays what the box can spare, up to the tip, to the miner's collection output
as capital the holding top-up aggregates, and beats for free when that is too small for a box of its own.
A beat is valid in exactly one block, the one whose height it is stamped with, so "anyone" means anyone building
for the next height; a non-miner can broadcast one, paying any fee out of the tip, and it is valid only if the very
next block includes it. `jobs.heartbeat.minTip` makes the job decline any beat that would pay less than that, free
beats included: a box offering less is not maintained, a box offering enough that cannot pay it is held until it
changes. No due-job box exists on mainnet yet; the README says how to create one, and testnet has one. By default a beat
must pay at least a thousandth of an ERG (`minTip`), so a box anyone paid dust into at the public script is not
re-stamped for free with its rent clock reset; 0 maintains every box. The contract's source is kept with the tests
(`test/resources/upkeep/DueJob.ergo`): the client carries only the pinned tree.

### Running on a private chain

The client compiles its protocol contracts from token ids fixed per network, so on a chain with no Lithos
deployment it finds no collateral box and never builds a Lithos block. Two small pieces change that, and they
are what let the source be tested end to end on a devnet rather than on mainnet:

- `node.deployment.file`: a JSON descriptor of a deployment (the token ids, the genesis dictionary box, the
  protocol box ids) that `DeploymentConfig.install` reads and validates at startup, in place of the network's constants. Empty by
  default; refused on mainnet unless `allowOnMainnet = true`; a descriptor that does not parse, names a
  malformed id, or names another network stops the client with the key at fault.
- `tools.DeployProtocol`: a deployer that mints the eight protocol tokens, creates the emission, config, fraud
  control and dictionary genesis boxes with the client's own contract code, writes the descriptor, and can
  fund operator keys with ERG and LIT. One transaction per mint, one for the protocol boxes and one for funding, each waited for until its first output
  is in the UTXO set. `DEVNET.md` documents both.

A spec pins every protocol contract's tree on mainnet and testnet (`test/resources/deployment/contract-pins.txt`)
so that neither piece can move a mainnet tree unnoticed; the pins were recorded from the base commit's own
compiler and this branch matches them. A second spec checks that an override reaches the thirteen contracts that
compile an id in and leaves `payout` and the other network's `collateral` unchanged. Reading any mainnet id never
requires compiling a contract: the one address mainnet has no constant for is pinned and checked against the compiled
script.

## Why

Storage rent showed that this client can carry keyless, fee-less work in its own blocks, and that a Lithos
miner is in a good place to do such work: it already builds the block, and the transaction pays no fee and needs no key; its cost is block
space a fee-paying transaction could have used. Rent is one rule; other protocols leave boxes that need periodic maintenance under rules of
their own, and today they wait for an executor paying a mempool fee. Generalising the rent source's shape
over a job registry lets a miner carry that maintenance for the protocols it opts into, and lets a
protocol's maintenance be added as one reviewed job rather than a new source each time.

## Off by default

`stratum.candidate.sources.upkeep.enabled = false` ships, and so does every job's flag. With the default
config nothing changes: no actor is started and no node read is made, by construction of the wiring. No actor is
started either while no
job is enabled. A job name that is enabled and unknown is refused at startup by config validation.

## Mempool and front-running

Upkeep's only use of the mempool is the read-back, which skips a box a pending transaction already spends; it never
reads what pending transactions do, and discovery reads confirmed boxes only. Every input of a job's transaction
must be a box that job reported and this build read back, and none may sit at this wallet's P2PK or miner-reward
scripts; every output must sit at an input's own script or this miner's collection contract, so no fee is paid and
no value goes to anyone else. The source refuses each of those for every job; a `ScriptJob` also makes the box the
only input and balances exactly, and a job implementing `UpkeepJob` directly is held to the rest (a successor that is
a fixed function of the box and the height) by review. A beat that reaches the mempool after the read-back loses to
this miner's block if it finds one. Because a beat is valid only at the height it is stamped with, the tip in
practice goes to whoever produces the next block, this miner or an executor whose beat that block includes.

## Limits

- A package the node rejects loses every transaction this client inserted into the block, whichever source
  built the bad one. That is a client-wide gap. This PR narrows it for upkeep with `verifyWithNode` (on by
  default), which puts every successor through the node's `/transactions/check` before it is offered, but it
  does not close it.
- By-script discovery reads at most the 1,000 newest boxes at a job's script per pass and keeps the soonest
  due of those, up to `maxBoxesPerJob`. Newest first because a beat gives a box a new id at the newest end, so a
  box kept alive stays in the window; anyone can still crowd it out with a stream of newer boxes at a public
  script, at a minimum box each per pass.
  Configured `boxIds` are never cut (at most 256 per job, each a read on every scan), but on a plain node
  they go stale after each beat, since the successor has a new id and a plain node cannot follow a spend.
- A refresh at the same height is answered from what was prepared: a successor is a fixed function of its box
  and the height. A build signs at most 16 successors it then cannot fit, and stops after 16 refusals. The
  read-back is up to 16 node calls of 256 boxes, in the build that starts when the height is known.
- With `useTruePropCollection`, the tip output is anyone-can-spend until the holding top-up in the same
  package takes it, as the rent source's capital is.
- A box both the rent source and upkeep could claim in one package (a due-job box nobody beat for four years) is
  admitted once: the builder keeps the first bundle and refuses the second as a conflicting spend.
- A package the node rejects is reported to the sources only as a dropped height, so a successor the node's
  check accepted and block validation refused is rebuilt next block; with `verifyWithNode` off that repeats.
  Client-wide, as for the rent source.
- With `minTip = 0`, anyone can create due-job boxes that pay nothing and claim upkeep's share of every block at
  the cost of one minimum box each; the default declines them.

## Follow-ups, not in this PR

- **Broadcast mode** — sending upkeep to the mempool with a fee from the operator's wallet when this
  miner finds no block. Left out because it spends operator ERG, which this client's own rule for block
  transactions forbids; it would be a separate, clearly marked option.
- **A first protocol job.** The framework is written so that one is an implementation, a registry entry and a
  config block; the heartbeat proves the source works, a protocol job is what proves it is worth having.

## Testing

- **Observe mode** (`stratum.candidate.sources.upkeep.mode = "observe"`) lets this be watched before any Lithos block
  carries it: every request is answered empty at once, and in the background the source builds and sizes everything as
  for a block, puts each successor through the node's `/transactions/check` and logs the verdict. To repeat on public
  testnet: a testnet node with `ergo.node.extraIndex = true`; in the client, `stratum.candidate.sources.upkeep.enabled =
  true`, `jobs.heartbeat.enabled = true`, `mode = "observe"`; expect `Upkeep scan holds 1 boxes: heartbeat=1`, then at
  most once per height (a height that arrives while the previous check still runs is skipped) after a box at the
  script is due, the one live there today being `e5d9d2c2…` (period 720, tip 0.01 ERG; its id changes with each
  beat, so look for the script's address, in the README, not the id), `Upkeep observe at <height>: heartbeat … the node's check accepts it`.
- Specs: `UpkeepSpec` (the pure half: cost accounting and floors against the node's own arithmetic for the token term,
  the share, the memory, config and validation), `UpkeepSourceSpec` (the actor against a mocked node: discovery, holds,
  the build's bounds, the wallet check, refresh, observe mode, the candidate protocol), `ScriptJobSpec` (what every
  script job inherits: discovery, keyless and fee-less assembly, the fee and revenue refusals), `HeartbeatJobSpec` (the
  pinned tree, due, the beat's terms, `minTip`), `DueJobSpec` (the contract through the interpreter, one property per
  condition), `DeployPlanSpec` (the deployer's plan and command line; the broadcasting `Deployer` itself is exercised only
  by the private-chain run), `DeploymentSpec`, `DeploymentConfigSpec` and `ProtocolContractsDeploymentSpec` (the
  override, and the contract pins, recorded from the base commit's compiler, as the pin file says, and checked
  against this branch's).
- End to end on a private chain with 20-second blocks: the deployer deployed the protocol, the client joined the
  collateral queue with its own ERG and LIT, and a block carried the client's genesis transaction and the upkeep beat
  together, the beat accepted by the node's check (the node's stateful validation at its next height, not the mempool's
  fee floor) and the block by consensus. The rig is outside this repository; `DEVNET.md` says what any private-chain
  run needs.
- `sbt test` on Java 17 at this branch's head: 2,770 tests, all passing. (A load-sensitive spec outside this change,
  `SnapshotFallbackSpec`, has a one-line fix in its own PR.)
