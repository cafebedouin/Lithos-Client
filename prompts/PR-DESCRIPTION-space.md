# Upkeep: an order by value, and an opportunistic count for space blocks leave empty

Stacked on the upkeep source PR, which it needs; that PR does not need this one. It changes the upkeep source, its
config, the `UpkeepJob` trait (one method), the heartbeat, and the wiring that hands the candidate builder upkeep's
count. Both pieces are options, off by default:

| Config | Who sees a change |
|---|---|
| unchanged | nobody, in what is built: the share, the order and the wiring are the upkeep PR's; each build parses every offered box up front, where it used to stop at a full share |
| `order = "value"` | upkeep operators who set it: due boxes are tried earliest-seen first, then by what a beat would pay |
| `space = "opportunistic"` | upkeep operators who set it: more transactions, within the configured bytes and cost, when the mempool leaves room |

## What

1. **`stratum.candidate.sources.upkeep.order`**: `"rotation"` (default, today's order: due boxes in id order started at
   the block height) or `"value"`. By value, of the due boxes whose floor fits the share, the one this client's scan saw first is tried first
   (a waiting box keeps its id, and a new box cannot backdate when it was seen, unlike the creation height a creator
   writes into it), so a due box that fits waits at most as many builds as there are due boxes seen before it; the
   rest of the due boxes go in order of expected revenue per floor byte, then per unit of floor cost, and a
   lower-paying due box waits while higher-paying due boxes fill the share. `UpkeepJob` gains `expectedRevenue(box, bc)`, 0 by default; the heartbeat
   returns what its beat would actually pay, computed as its plan computes it, never more than the box can pay
   whatever R6 declares (anyone can create a box at the script, and a payment the box cannot make would otherwise buy
   it an early slot for nothing; with `minTip` set, a box that cannot pay it is worth nothing and is declined). The
   sort is stable, so boxes worth the same keep the rotation's order.
2. **`stratum.candidate.sources.upkeep.space`**: `"fixed"` (default, today's count) or `"opportunistic"`.
   - *What it reads.* When upkeep has more due boxes than slots, the build reads the mempool once (pages of
     `/transactions/unconfirmed`, the first 2,000 waiting transactions, fee or not, 2,000 or more counting as full; up
     to 20 node calls; a read found past 2 seconds before a page or at its end fails, while a single call that hangs is
     cut short only by the node client). It counts bytes and cost only, and a figure of zero fails the read.
   - *When it grows.* When what is waiting fits, by the node's figures at the read, in the rest of the block beside
     this client's whole package share (`blockShare` of the block limits) less a reserve for the node's own emission
     and fee transactions, upkeep may take up to the larger of `maxTxs` and `opportunisticMaxTxs` (default 20)
     transactions, still within its configured `maxBytes` and `maxCost`. At the defaults that is 15 more successors
     of about 250 bytes and 12,000 cost each: about 4 KB and 180,000 cost of a block, out of upkeep's own 256 KB and
     1,000,000. With `verifyWithNode` the node checks up to that many successors, in turn, in the same build.
   - *What keeps the configured count.* A mempool too full for that, one deeper than 20 pages, a page that cannot be
     read, a transaction the node reports without a size or cost (6.0.x reports both), or a read past its 2 seconds:
     every failure the read can detect. Nothing arriving after the read is counted, and offset paging over a mempool
     that changes between pages can skip a transaction. The count is decided once per build: once per height, unless
     that height's package is dropped and rebuilt.
   - *The hard bound.* The candidate builder's bytes and cost passes stand unchanged, per source and package-wide
     after genesis; in opportunistic mode the wiring raises upkeep's builder count to the cap in every block, so
     whatever the source does, upkeep never takes more bytes or cost than its configured share, never more than the
     package share leaves, and never more transactions than the larger of `maxTxs` and `opportunisticMaxTxs`.
   - *What it amounts to.* The read is taken at the start of a height, right after a block emptied the mempool, and
     the count it decides holds for the height. In practice the mode is `maxTxs` raised to the cap with a back-off
     when the mempool is busy; an operator who wants the count without the back-off can set `maxTxs = 20` today.

## Why

Ergo blocks are mostly empty, and a fee-less upkeep transaction almost never displaces a paying one. A fixed count in
a fixed order wastes the room an empty block offers and can spend a full share on the work that pays least.

## The policy question

Opportunistic mode takes transactions' worth of space that, by the node's figures at the read, no transaction waiting
then needs, within the bytes and cost the operator already allowed upkeep in fixed mode. By this PR's own premise
that blocks are mostly empty, the check passes in nearly every block, so in practice opportunistic means up to the
cap. It cannot know whether a paying transaction arriving later, while the block is being mined, would have wanted
that space. Whether fee-less work should take space a later paying transaction might have used is the maintainer's
call. That is why the mode is off by default and capped. The order is a second policy, also off by default: a
lower-paying due box waits while higher-paying ones fill the share, which is what ordering by value means, with the
earliest-seen due box first so that the wait is bounded; `minTip` on the heartbeat is the operator's bound on free
beats, and the ranking cannot be bought with a tip a box cannot pay.

Opportunistic mode also reads the mempool. It counts bytes and cost only, and it does not look at what pending
transactions do, so the upkeep PR's "not extractive" still holds.

## Testing

- `UpkeepSpec`: the ordering (per byte, then per cost, stable, no revenue last); the opportunistic count (waiting
  transactions that do not fit keep the configured share, ones that fit raise the count within the configured bytes
  and cost, an exact fit does not fit, the configured count wins over a smaller cap); the demand read (sums, early
  stop, a full mempool, saturation, a transaction without size or cost or with a figure of zero failing the read, a
  later page failing the read, a read past its deadline failing before a page or at its end); the head by age; config defaults, parsing, validation of `space`, `order` and the cap, and
  the builder allowance in isolation.
- `UpkeepSourceSpec`: with a fixed set of three boxes declaring different revenues (the fake job's `expectedRevenue`;
  what its build pays is constant) and two slots, by value each height admits the earliest-seen box (all seen in one
  pass, a tie, so the rotation's head) and the highest revenue of the rest, and builds only those; by the default order the same boxes
  are built in rotation order whatever they declare. Opportunistic mode with an empty mempool, or one that fits
  beside the package, takes up to the cap; with waiting transactions that do not fit, or a read failure, the
  configured count; with no more due boxes than slots it does not read the mempool; fixed mode never does. Nothing
  drives `CandidateBuilder` with an opportunistic source; the builder's package pass is covered by its own specs.
- `HeartbeatJobSpec`: expected revenue is what the beat would pay: the tip, what the box can spare, or 0 for a free
  or declined beat, a malformed box, or a declared tip the box cannot pay.
- Run with `sbt -batch "testOnly transactions.upkeep.*"` on Java 17.
