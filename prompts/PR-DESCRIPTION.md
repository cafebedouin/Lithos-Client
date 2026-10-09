# Add an upkeep candidate source: keyless maintenance of other protocols' boxes in the miner's block

## What

A new transaction source, `upkeep`, that advances boxes other protocols leave for anyone to maintain,
inside this miner's own block candidate, with no key and no fee. It is a registry of jobs: each job is a
reviewed description of one protocol's boxes — which ones it maintains, when one is due, and what its
successor is — and the source around it owns everything else: a discovery timer, revalidation by reading
the boxes back before a block is built, sizing and fitting to the source's share, a memory of refusals,
and the prepare/request/drop protocol every candidate source speaks.

A job whose boxes sit at one known script and whose successor is a fixed function of the box — the heartbeat,
a Dexy tracker, an expiry refund — extends `ScriptJob`, and is then its rule and nothing else: `contract`,
`due`, and `plan` returning a `Successor` (outputs, data inputs, which outputs are revenue). `ScriptJob` owns
discovery by script through the node's index plus the configured `boxIds` fallback, assembly with no fee and
no wallet input, signing with a prover that holds no key, and the capital entries. Jobs are registered as
factories from their own config block, so a new protocol is one file and one config block; a job that does
not fit the script shape implements `UpkeepJob` directly. The first job, `heartbeat`, advances a minimal self-describing
due-job box (`upkeep/DueJob.ergo`: R4 last beat, R5 period, R6 tip; spendable by anyone once due; the
successor must keep the script, tokens and terms and at most the tip leaves), and pays the tip to the
miner's collection output as capital the holding top-up aggregates.

## Why

Storage rent showed that this client can carry keyless, fee-less work in its own blocks, and that a Lithos
miner is in a good place to do such work: it already builds the block, and the transaction costs it nothing
to include. Rent is one rule; other protocols leave boxes that need periodic maintenance under rules of
their own, and today they wait for an executor paying a mempool fee. Generalising the rent source's shape
over a job registry lets a miner carry that maintenance for the protocols it opts into, and lets a
protocol's maintenance be added as one reviewed file rather than a new source each time.

## Off by default

`stratum.candidate.sources.upkeep.enabled = false` ships, and so does every job's flag. With the default
config nothing changes: no actor is started and no node read is made. With the source on and no job on,
the source answers every request empty. A job name config turns on that the registry does not know fails
config validation at startup.

## Not extractive

Nothing here reorders, front-runs, sandwiches or replaces anyone's transaction. A job's transaction may
only spend the boxes that job discovered; the source refuses one that spends anything else, so the
miner's wallet is never an input and no fee is paid. The source reads only the boxes its jobs maintain,
and never the mempool.

## Follow-ups, not in this PR

- **Broadcast mode** — sending upkeep to the mempool with a fee from the operator's wallet when this
  miner finds no block. Left out because it spends operator ERG, which this client's own rule for block
  transactions forbids; it would be a separate, clearly marked option.
- **A Dexy job** against the relaunched contracts, to be coordinated with the maintainer, who plans that
  integration. The framework is written so that it is one file plus one config block.

## Testing

- **Observe mode** (`stratum.candidate.sources.upkeep.mode = "observe"`) lets this be watched on mainnet before
  any Lithos block carries it: the source builds and sizes everything as for a block, puts each successor
  through the node's `/transactions/check`, logs the verdict and what it would have offered, and answers
  empty. The operator soaks the heartbeat this way against a real due-job box.
- `UpkeepSpec`: the pure half — the node's cost accounting and the floor's token term against the node's own
  arithmetic, the share offered successors in turn, the refusal memory's retry rule, config loading and
  defaults, job factories reading their own keys, and validation of modes, job blocks and every job's
  `boxIds`.
- `UpkeepSourceSpec`: the actor against a mocked node and a steerable job — disabled and idle sources make
  no node read; discovery, revalidation, the per-job box cap and a job whose discovery throws; due
  versus refused; a job that throws on one box losing only that box; a box the node reports unreadable;
  refusals remembered across blocks and across an actor restart, forgotten when the box changes, and
  retried after `retryAfterScans` passes; a transaction over `maxCost` left out while a cheaper one
  fits; building stopping at `maxTxs`; observe mode answering empty with one node check per built
  successor and per height; and the prepare/request/drop protocol.
- `ScriptJobSpec`: what every script job inherits, through a minimal fake — discovery on an indexed node
  (paged, capped, with the index down) and on a plain node from the configured list; a plan signed with no
  key and no fee, spending only the box, with its revenue declared; a data input carried and not spent.
- `HeartbeatJobSpec`: the heartbeat's own rule — which boxes at its script are beats, `due` at the boundary,
  the successor and tip as planned and signed, and a box that can no longer pay its beat building nothing.
- `DueJobSpec`: the contract through the interpreter, offline — a due box advances, one block early is
  refused, and every condition of the script refused on exactly the field it reads.
- `sbt -batch test` on Java 17 (to be confirmed by the operator before this is opened).
