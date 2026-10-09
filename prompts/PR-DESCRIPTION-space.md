# Upkeep: best-paying work first, and an opportunistic count for space blocks leave empty

Stacked on the upkeep source PR, which it needs; that PR does not need this one. It changes the upkeep
source, its config, the `UpkeepJob` trait (one method), the heartbeat, and the wiring that hands the
candidate builder upkeep's limits. With an unchanged config the share is the same as the upkeep PR's. What
changes is the order due boxes are built in when a job can say what a beat would pay (the heartbeat can), so
an operator running the heartbeat in fixed mode gets the new order too: boxes with unequal payable tips are
built highest tip per byte first, after the box at the head of the height rotation.

## What

1. **Due work in order of value per byte.** `UpkeepJob` gains `expectedRevenue(box, bc)`, 0 by default.
   The heartbeat returns what its beat would actually pay, computed as its plan computes it from the box's
   value and the floors, never the R6 tip as declared: anyone can create a box at the script, and a
   declared tip the box cannot pay would otherwise buy it the first slot for nothing; with `minTip` set, a
   box that cannot pay it is worth nothing and is declined. Before building, the source puts the box at
   the head of the height rotation first, so every box reaches the head once per cycle and none waits
   forever, and ranks the rest by expected revenue per floor byte, then per unit of floor cost; a
   lower-paying due box waits for as long as higher-paying ones are due. The sort is stable, so boxes
   worth the same keep the rotation's order, and a job that declares nothing keeps today's order exactly.
2. **`stratum.candidate.sources.upkeep.space`**: `"fixed"` (default, today's share) or `"opportunistic"`.
   Opportunistic, each build reads the mempool once (pages of `/transactions/unconfirmed`, up to 2,000
   waiting transactions, fee or not, a deeper mempool counting as full; `poolHistogram` reports counts and
   fees, not bytes or cost) and, when what is waiting fits by the node's figures in the rest of the block
   beside this client's whole package share (`blockShare` of the block limits), lets upkeep take up to the
   larger of `maxTxs` and `opportunisticMaxTxs` (default 20) transactions, still within its configured
   `maxBytes` and `maxCost`. The growth is in the count only, so the bytes and cost the operator set bound
   upkeep as in fixed mode, and the builder's per-source and package passes stand; in opportunistic mode
   the wiring raises upkeep's builder allowance on the count alone, and the package-wide count rises with
   it. A mempool too full for that, one deeper than 20 pages, a page that cannot be read, or a transaction
   the node reports without a size or cost keeps the configured count: every failure the read can detect
   keeps the configured share. The node's own emission and fee transactions are not in the mempool and are
   not counted, nor is anything arriving after the read, and offset paging over a mempool that changes
   between pages can skip a transaction. A refresh at the same height is answered from what was prepared,
   so the count is decided once per height.

## Why

Ergo blocks are mostly empty, and a fee-less upkeep transaction almost never displaces a paying one.
A fixed count in a fixed order wastes the room an empty block offers and can spend a full share on the
work that pays least.

## The policy question

Opportunistic mode takes transactions' worth of space that, by the node's figures at the read, no
transaction waiting then needs, within the bytes and cost the operator already allowed upkeep. It cannot
know whether a paying transaction arriving later, while the block is being mined, would have wanted that
space. Whether fee-less work should take space a later paying transaction might have used is the
maintainer's call. That is why the mode is off by default and capped. The ordering is a second, smaller
policy: a lower-paying due box waits while higher-paying ones are due, which is what ordering by value means,
with one slot per build kept for the rotation so that nothing waits forever; `minTip` on the heartbeat is
the operator's bound on free beats, and the ranking cannot be bought with a declared tip.

Opportunistic mode also reads the mempool. It counts bytes and cost only, and it does not look at what
pending transactions do, so the upkeep PR's "not extractive" still holds.

## Testing

- `UpkeepSpec`: the ordering (per byte, then per cost, stable, no revenue last); the opportunistic count
  (waiting transactions that do not fit beside a full package keep the configured share, ones that fit
  raise the count within the configured bytes and cost, the configured count wins over a smaller cap);
  the demand read (sums, early stop, a deep mempool charged as full, sums saturating at the budget, a
  transaction without size or cost failing the read, a later page failing the read); config defaults,
  parsing, validation and the builder allowance in isolation.
- `UpkeepSourceSpec`: due boxes with different payable tips and two slots admit the rotation's head and
  the highest tip per byte of the rest, and build only those. Opportunistic mode with an empty mempool,
  or one that fits beside the package, takes up to the cap. With waiting transactions that do not fit,
  or a mempool read failure, it takes the configured count. Fixed mode never reads the mempool. Nothing
  drives `CandidateBuilder` with an opportunistic source; the builder's package pass is covered by its
  own specs.
- `HeartbeatJobSpec`: expected revenue is what the beat would pay: the tip, what the box can spare, or 0
  for a free or declined beat, a malformed box, or a declared tip the box cannot pay.
- Run with `sbt -batch "testOnly transactions.upkeep.*"` on Java 17.
