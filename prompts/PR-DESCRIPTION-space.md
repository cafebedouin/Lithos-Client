# Upkeep: best-paying work first, and an opportunistic share for space blocks leave empty

Stacked on the upkeep source PR and independent of it otherwise: it changes only the upkeep source, its
config, and the line of wiring that hands the candidate builder upkeep's limits. It can be merged or
closed on its own. With an unchanged config, behaviour is the same as the upkeep PR's.

## What

1. **Due work in order of value per byte.** `UpkeepJob` gains `expectedRevenue(box)`, 0 by default; the
   heartbeat returns its R6 tip. Before building, the source ranks offered boxes by expected revenue per
   floor byte, then per unit of floor cost, so the share is spent on the best-paying work first and a
   full share stops the build. The sort is stable, so boxes worth the same keep the existing height
   rotation, and a job that declares nothing keeps today's order exactly.
2. **`stratum.candidate.sources.upkeep.space`**: `"fixed"` (default, today's behaviour) or
   `"opportunistic"`. Opportunistic, each build reads the mempool once (pages of
   `/transactions/unconfirmed`; `poolHistogram` reports counts and fees, not bytes or cost), subtracts
   that demand from the package budget the candidate builder uses
   (`CandidateBudget.of(maxBlockSize, maxBlockCost, blockShare)`), and lets upkeep grow into the
   remainder. It never goes past the remainder or past `opportunisticMaxTxs` (default 20), and never
   below the configured share. A full mempool, one deeper than 20 pages, or a failed read keeps the
   configured share. The builder bounds each source by its limits again, so in opportunistic mode only
   the wiring raises upkeep's builder allowance to the cap. Package-wide admission still applies.

## Why

Ergo blocks are mostly empty, and a fee-less upkeep transaction almost never displaces a paying one.
A fixed share in a fixed order wastes the room an empty block offers and can spend a full share on the
work that pays least.

## The policy question

Opportunistic mode takes space that no transaction waiting **now** wants. It cannot know whether a
paying transaction arriving later, while the block is being mined, would have wanted that space. Whether
fee-less work should take space a later paying transaction might have used is the maintainer's call. That
is why the mode is off by default and capped.

Opportunistic mode also reads the mempool. It counts bytes and cost only, and it does not look at what
pending transactions do, so the upkeep PR's "not extractive" still holds. That PR's line "nothing reads
pending transactions" now needs the qualifier "except to count them, in opportunistic mode".

## Testing

- `UpkeepSpec`: the ordering (per byte, then per cost, stable, no revenue last); the opportunistic share
  (demand over budget keeps the configured share, an empty mempool lifts it to the remainder, the cap
  holds, the share never drops below configured); the demand read (sums, early stop, deep mempool
  charged as full, missing cost charged at the block's cost per byte, failed page fails); config
  defaults, parsing, validation and the builder allowance.
- `UpkeepSourceSpec`: three due boxes with different tips and two slots admit the two highest tips
  and build only those. Opportunistic mode with an empty mempool admits up to the cap. With demand over
  the budget, or a mempool read failure, it admits the configured share. Fixed mode never reads the
  mempool.
- `HeartbeatJobSpec`: expected revenue is the R6 tip, or 0 for a box that is not a beat.
- Run with `sbt -batch "testOnly transactions.upkeep.*"` on Java 17.
