# Upkeep: best-paying work first, and an opportunistic share for space blocks leave empty

Stacked on the upkeep source PR and independent of it otherwise: it changes only the upkeep source, its
config, and the line of wiring that hands the candidate builder upkeep's limits. It can be merged or
closed on its own. With an unchanged config the share is the same as the upkeep PR's; what changes is the order due
boxes are built in when a job declares revenue (the heartbeat does: its tip), so boxes with unequal tips are no longer
built in height rotation but best-paying first.

## What

1. **Due work in order of value per byte.** `UpkeepJob` gains `expectedRevenue(box)`, 0 by default; the
   heartbeat returns its R6 tip. Before building, the source ranks offered boxes by expected revenue per
   floor byte, then per unit of floor cost, so the share is spent on the best-paying work first and a
   full share stops the build. The sort is stable, so boxes worth the same keep the existing height
   rotation, and a job that declares nothing keeps today's order exactly.
2. **`stratum.candidate.sources.upkeep.space`**: `"fixed"` (default, today's behaviour) or
   `"opportunistic"`. Opportunistic, each build reads the mempool once (pages of
   `/transactions/unconfirmed`, every transaction waiting; `poolHistogram` reports counts and fees, not
   bytes or cost) and, when what is waiting fits in the rest of the block beside this client's whole
   package share (`blockShare` of the block limits), lets upkeep grow to that package share, with the
   count raised to `opportunisticMaxTxs` (default 20) or the configured `maxTxs` if that is larger.
   The growth never displaces a transaction already waiting, and never goes below the configured
   share. A mempool too full for that, one deeper than 20 pages, a page that cannot be read, or a
   transaction the node reports without a size or cost keeps the configured share: the read errs only
   toward growing less. The builder bounds each source by its limits again, so in opportunistic mode
   only the wiring raises upkeep's builder allowance; the builder's package pass then fits every
   source, upkeep last, into the package share as before.

## Why

Ergo blocks are mostly empty, and a fee-less upkeep transaction almost never displaces a paying one.
A fixed share in a fixed order wastes the room an empty block offers and can spend a full share on the
work that pays least.

## The policy question

Opportunistic mode takes space that no transaction waiting **now** needs: it grows only when everything
waiting fits in the block beside a full package. It cannot know whether a paying transaction arriving
later, while the block is being mined, would have wanted that space. Whether fee-less work should take
space a later paying transaction might have used is the maintainer's call. That is why the mode is off by
default and capped. The ordering is a second, smaller policy: a cheaper due box waits while dearer ones are
due, which is what ordering by value means; `minTip` on the heartbeat is the operator's bound on free
beats taking slots.

Opportunistic mode also reads the mempool. It counts bytes and cost only, and it does not look at what
pending transactions do, so the upkeep PR's "not extractive" still holds. That PR's line "nothing reads
pending transactions" now needs the qualifier "except to count them, in opportunistic mode".

## Testing

- `UpkeepSpec`: the ordering (per byte, then per cost, stable, no revenue last); the opportunistic share
  (waiting transactions that do not fit beside a full package keep the configured share, ones that fit
  lift it to the package, a package no larger than the configured share keeps it, the configured count
  wins over a smaller cap); the demand read (sums, early stop, a deep mempool charged as full, sums
  saturating at the budget, a transaction without size or cost failing the read, a later page failing
  the read); config defaults, parsing, validation and the builder allowance in isolation.
- `UpkeepSourceSpec`: three due boxes with different tips and two slots admit the two highest tips
  and build only those. Opportunistic mode with an empty mempool admits up to the cap. With waiting
  transactions that do not fit beside the package, or a mempool read failure, it admits the configured
  share. Fixed mode never reads the mempool. Nothing drives `CandidateBuilder` with an opportunistic
  source; the builder's package pass is covered by its own specs.
- `HeartbeatJobSpec`: expected revenue is the R6 tip, or 0 for a box that is not a beat.
- Run with `sbt -batch "testOnly transactions.upkeep.*"` on Java 17.
