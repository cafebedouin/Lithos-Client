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
No due-job box exists on mainnet yet.

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

Nothing reads pending transactions, so nothing reorders or front-runs anyone. A job's transaction may only
spend boxes it discovered; the source refuses one that spends anything else. The shipped job finds boxes by
script and signs with no key and no fee. The read-back is the node's mempool-adjusted view, so a box a
pending transaction already spends is skipped for that block.

## Limits

- A package the node rejects loses every transaction this client inserted into the block, whichever source
  built the bad one. That is a client-wide gap. This PR narrows it for upkeep with `verifyWithNode` (on by
  default), which puts every successor through the node's `/transactions/check` before it is offered, but it
  does not close it.
- Discovery keeps the soonest-due boxes up to `maxBoxesPerJob`, so a flood of cheap due boxes at a public
  script can still crowd a job's real ones out of the cap. Configured `boxIds` are never cut.
- A refresh rebuilds the source's work for the height, as the rent source does.

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
- `DueJobSpec`: the contract through the interpreter, offline — a due box advances, one block early is
  refused, every condition of the script refused on the field it reads, two boxes sharing one successor
  refused, a stale creation height refused, a zero period refused, a tip above the value taking all but a
  box's minimum, and R4 + R5 computed in Long.
- `sbt test` on Java 17: 2,698 tests. The only failures are the 8 cases of `state.persistence.SnapshotFallbackSpec`,
  which is load-sensitive and fails the same way without this change: "keep a generation whose header could
  not be read", "restore a generation the node confirms", "fall past a disproved generation to an older
  confirmed one", "report no canonical snapshot only when every generation was checked", "terminate a restore
  request on restart and ignore its stale validation completion", "refuse a byte-valid generation whose fields
  contradict each other", "refuse a generation whose retained cursors do not reach its own", and "refuse
  retained cursor identity from a same-height fork".
