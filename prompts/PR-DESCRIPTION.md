# Add an upkeep candidate source: keyless maintenance of other protocols' boxes in the miner's block

## What

A new transaction source, `upkeep`, that advances boxes other protocols leave for anyone to maintain,
inside this miner's own block candidate, with no key and no fee. It is a registry of jobs: each job is a
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
for the next height; a non-miner can broadcast one and have it land in that block. `jobs.heartbeat.minTip` lets an
operator decline boxes offering less than a chosen tip, free beats included. No due-job box exists on mainnet yet. Lithos itself has mined about 30 mainnet blocks so far (heights 1,888,828 to
1,890,575, about 1.7% of blocks over that span), so upkeep carried only in Lithos blocks waits about 60 blocks today.

### Running on a private chain

The client compiles its protocol contracts from token ids fixed per network, so on a chain with no Lithos
deployment it finds no collateral box and never builds a Lithos block. Two small pieces change that, and they
are what let the source be tested end to end on a devnet rather than on mainnet:

- `node.deployment.file`: a JSON descriptor of a deployment (the token ids, the genesis dictionary box, the
  protocol box ids) that `Deployment.install` reads at startup in place of the network's constants. Empty by
  default; refused on mainnet unless `allowOnMainnet = true`; a descriptor that does not parse, names a
  malformed id, or names another network stops the client with the key at fault.
- `tools.DeployProtocol`: a deployer that mints the eight protocol tokens, creates the emission, config, fraud
  control and dictionary genesis boxes with the client's own contract code, writes the descriptor, and can
  fund operator keys with ERG and LIT. One transaction per step, each waited for. `DEVNET.md` documents both.

A spec pins every protocol contract's tree on mainnet and testnet (`test/resources/deployment/contract-pins.txt`)
so that neither piece can move a mainnet tree unnoticed, and checks that an override reaches every contract
that compiles an id in and nothing else.

## Why

Storage rent showed that this client can carry keyless, fee-less work in its own blocks, and that a Lithos
miner is in a good place to do such work: it already builds the block, and the transaction costs it nothing
to include. Rent is one rule; other protocols leave boxes that need periodic maintenance under rules of
their own, and today they wait for an executor paying a mempool fee. Generalising the rent source's shape
over a job registry lets a miner carry that maintenance for the protocols it opts into, and lets a
protocol's maintenance be added as one reviewed job rather than a new source each time.

## Off by default

`stratum.candidate.sources.upkeep.enabled = false` ships, and so does every job's flag. With the default
config nothing changes: no actor is started and no node read is made. No actor is started either while no
job is enabled. A job name that is enabled and unknown is refused at startup by config validation.

## Not extractive

Upkeep never looks at pending transactions to decide what to build. Its read-back uses the node's
mempool-adjusted view, so a box a pending transaction already spends is dropped until the next scan; a spend
that reaches the mempool after the read-back loses to this miner's own block, as with any block producer.

A job's transaction may only spend boxes that job reported from discovery; the source refuses one that
spends anything else, which holds a job to its own word. The shipped job reports only boxes at its own
script and signs with no key and no fee, so it cannot spend the operator's wallet; a job that implements
`UpkeepJob` directly is held to that by review.

## Limits

- A package the node rejects loses every transaction this client inserted into the block, whichever source
  built the bad one. That is a client-wide gap. This PR narrows it for upkeep with `verifyWithNode` (on by
  default), which puts every successor through the node's `/transactions/check` before it is offered, but it
  does not close it.
- By-script discovery reads at most the 1,000 oldest boxes at a job's script per pass and keeps the soonest
  due of those, up to `maxBoxesPerJob`. Anyone can create boxes at a public script, so 1,000 older boxes
  there, due or not, hide every newer one from an indexed client, and a beat gives a real box a newer index.
  Configured `boxIds` are never cut (at most 256 per job, each a read on every scan), but on a plain node
  they go stale after each beat, since the successor has a new id and a plain node cannot follow a spend.
- A refresh rebuilds the source's work for the height, as the rent source does. A build signs at most 16
  successors it then cannot fit, and stops after 16 refusals.
- With `useTruePropCollection`, the tip output is anyone-can-spend until the holding top-up in the same
  package takes it, as the rent source's capital is.

## Follow-ups, not in this PR

- **Broadcast mode** — sending upkeep to the mempool with a fee from the operator's wallet when this
  miner finds no block. Left out because it spends operator ERG, which this client's own rule for block
  transactions forbids; it would be a separate, clearly marked option.
- **A Dexy job** against the relaunched contracts. The framework is written so that it is one job, one
  registry entry and one config block.

## Testing

- **Observe mode** (`stratum.candidate.sources.upkeep.mode = "observe"`) lets this be watched on mainnet before
  any Lithos block carries it: every request is answered empty at once, and in the background the source
  builds and sizes everything as for a block, puts each successor through the node's `/transactions/check`
  (up to `maxTxs` checks per block) and logs the verdict.
- `UpkeepSpec`: the pure half — the node's cost accounting and the floor's token term against the node's own
  arithmetic, the share offered successors in turn, the memory's retry rule and its hold on boxes that
  cannot pay, config loading and defaults, job factories reading their own keys, and validation of modes,
  `verifyWithNode`, job blocks and every job's `boxIds`.
- `UpkeepSourceSpec`: the actor against a mocked node and a steerable job — no source without an enabled job;
  the first scan on the timer; discovery, chunked read-back, the per-job cap that never cuts configured ids,
  and a job whose discovery throws; due, exhausted and refused; a build stopping after 16 refusals; a job that
  throws on one box losing only that box; a box the node reports unreadable; holds kept across blocks and an
  actor restart, forgotten when the box changes, and refusals retried after `retryAfterScans` passes; a
  transaction over `maxCost` left out while a cheaper one fits; building stopping at `maxTxs`; the box order
  rotating with the height; the node's check accepting, refusing, or switched off; observe mode answering empty
  with one background check per successor and per height; and the prepare/request/drop protocol.
- `ScriptJobSpec`: what every script job inherits, through a minimal fake — discovery on an indexed node
  (paged, capped, ordered by priority, skipping re-emission boxes, with the index down) and the configured
  list read by id from the UTXO set on any node; a plan signed with no key and no fee, spending only the box,
  with its revenue declared; a plan that leaves change refused; a data input carried and not spent.
- `HeartbeatJobSpec`: the heartbeat's own rule — the pinned tree, which boxes at its script are beats, `due`
  at the boundary, the successor and tip as planned and signed, a partial tip when the box cannot spare the
  whole, and a free beat when what it can spare is too small for a box.
- `DeployPlanSpec` and `ProtocolContractsDeploymentSpec`: the deployer's plan (mint, protocol boxes, descriptor,
  funding) against a fake node, the self-join a funded operator can make with the client's own builders, the
  refusal of funding below one join, the command line; and the contract pins: with no override every tree is
  the pinned one, an override reaches every contract that compiles an id in and leaves `payout` and the other
  network alone, and it is not served from a cache filled before it was installed.
- `DueJobSpec`: the contract through the interpreter, offline — a due box advances, one block early is
  refused, every condition of the script refused on the field it reads, two boxes sharing one successor
  refused, a stale creation height refused, a zero period refused, a tip above the value taking all but a
  box's minimum, and R4 + R5 computed in Long.
- End to end. A due-job box is live on testnet (`e5d9d2c2…`, period 720, tip 0.01 ERG). On a private chain
  with 20-second blocks, one command of a test rig goes from a wiped chain to a block the
  client built: the deployer deploys the protocol, the client joins the collateral queue with its own ERG and
  LIT, and a block carries the client's genesis transaction and the upkeep beat together, the beat accepted
  by the node's check (which runs the node's stateful validation at its next height, not the mempool's fee
  floor) and the block by consensus. The rig (topology, node settings, an `/info` rewriting proxy for appkit,
  a CPU miner) is a test rig outside this repository; `DEVNET.md` says what any private-chain run needs.
- `sbt test` on Java 17: 2,784 tests, all passing (run with the stacked follow-on included). One spec outside this
  change, `state.persistence.SnapshotFallbackSpec`, is load-sensitive: its first LevelDB open can exceed TestKit's
  3-second expectation when the host is busy, and eight of its cases then fail together without this change. A
  separate one-line PR gives it the 20 s the other actor specs allow.
