{
  // KeepAlive receive address: a vault a payer can pay like any address, kept safe from storage rent by anyone.
  //
  // The owner's key is a constant of the script, so each owner has one P2S address; a payment to it needs no
  // registers. The constant is segregated, so every owner's vault shares one template hash and executors find them all.
  //
  // MAINTENANCE (no key; context variable 0 names the successor output): every input at this script ("mine") is
  // merged into that one output, which must
  //   - be at this script (same owner),
  //   - hold at least the total of every token across mine,
  //   - hold at least the total value of mine less the bounty,
  //   - be created at most SLACK blocks before the spending height,
  //   - be no larger than the inputs it replaces (one input: + 4 bytes, for value and height encodings).
  // When: a merge (two or more of mine) at any time; a lone box only from WINDOW blocks before it is PERIOD old.
  // Bounty: a merge takes at most PER_INPUT per input and never more than the boxes other than the largest bring
  // (total - largest), so the largest box never pays for a merge and dust sent to the address cannot drain it; a lone
  // refresh takes at most REFRESH, once per period. Every input of mine checks the same output, so two outputs cannot
  // each claim the whole set (double satisfaction).
  //
  // Constants (mainnet / devnet): PERIOD 1051200 / 40, WINDOW 21600 / 20, PER_INPUT 500000, REFRESH 2000000,
  // SLACK 10; OWNER the owner's compressed public key.
  val owner = proveDlog(decodePoint(fromBase16("$OWNER")))
  // How much of one token a box holds; one tuple argument, as the compiler builds single-argument lambdas
  val amt = { (p: (Box, Coll[Byte])) => p._1.tokens.fold(0L, { (a: Long, t: (Coll[Byte], Long)) =>
    if (t._1 == p._2) a + t._2 else a }) }
  val idx = getVar[Int](0)
  val maintain = if (idx.isDefined) {
    val next = OUTPUTS(idx.get)
    val mine = INPUTS.filter({ (b: Box) => b.propositionBytes == SELF.propositionBytes })
    val tokensKept = mine.forall({ (v: Box) => v.tokens.forall({ (t: (Coll[Byte], Long)) =>
      amt((next, t._1)) >= mine.fold(0L, { (a: Long, w: Box) => a + amt((w, t._1)) }) }) })
    val total = mine.fold(0L, { (a: Long, v: Box) => a + v.value })
    val largest = mine.fold(0L, { (a: Long, v: Box) => max(a, v.value) })
    val merge = mine.size >= 2
    val bounty = if (merge) min(mine.size.toLong * $PER_INPUTL, total - largest) else min(SELF.value / 2, $REFRESHL)
    val sizeOk = if (merge) next.bytes.size <= mine.fold(0, { (a: Int, v: Box) => a + v.bytes.size })
                 else next.bytes.size <= SELF.bytes.size + 4
    allOf(Coll(
      merge || HEIGHT >= SELF.creationInfo._1 + $PERIOD - $WINDOW,
      next.propositionBytes == SELF.propositionBytes,
      tokensKept,
      next.value >= total - bounty,
      next.creationInfo._1 >= HEIGHT - $SLACK,
      sizeOk
    ))
  } else false
  owner || sigmaProp(maintain)
}
