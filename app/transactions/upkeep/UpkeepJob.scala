package transactions.upkeep

import node.NodeApi
import org.ergoplatform.appkit.{BlockchainContext, SignedTransaction}
import transactions.candidate.CapitalEntry
import work.lithos.mutations.{Contract, InputUTXO}

/**
 * One kind of keyless, fee-less maintenance this miner may carry in its own block.
 *
 * A job is a reviewed description of one protocol's boxes: which ones it maintains, when one is
 * due, and what its successor is. The source around it owns everything else — the timer, the
 * revalidation, the budget, the refusals — so a new protocol is one implementation of this trait
 * and one config line, with no actor code of its own to get wrong.
 *
 * The rules a job has to keep. The framework enforces the first where it can and takes the rest on
 * trust, which is why every job is reviewed before it is registered:
 *
 *  1. No wallet input, no fee output. The inputs are the boxes the job discovered, and nothing
 *     else: the source refuses a transaction spending any other box. This miner's own ERG is never
 *     on the line, and a block transaction carries no fee.
 *  2. A deterministic successor. Given the box and the height, the outputs are fixed. No search,
 *     no pricing, no market reads: a job that has to choose is not upkeep, however keyless it is.
 *  3. Revenue goes to `payTo`, the collection contract the source hands in, and is declared as a
 *     [[transactions.candidate.CapitalEntry]] so the package's final top-up can aggregate it. A
 *     job may earn nothing.
 *  4. Discovery returns ids, and the build gets the box read back fresh. What a scan found is
 *     stale by the time a block is built, so nothing a job learned at discovery may be trusted at
 *     build time.
 *  5. "Not yet" belongs in [[due]], "cannot" in [[build]]. A box `build` returns nothing for, or
 *     throws on, is remembered as refused and not tried again until it changes or until the
 *     configured number of discovery passes has gone by, so a job that says no to a box it will
 *     be able to advance next block stalls that box for that long.
 *  6. Bounded: the source sizes every due box against what is left of its own share before the
 *     job builds it, measures what the job built, and fits that to the share before the package
 *     does. A job need not count; it may not understate.
 *  7. Never extractive. Nothing here reorders, front-runs or sandwiches anyone's transaction, and a
 *     job that would is not accepted into the registry.
 */
trait UpkeepJob {

  /**
   * The config key under `stratum.candidate.sources.upkeep.jobs`, and what a candidate's `kind`
   * names, so a refused block says which job built the transaction.
   */
  def name: String

  /**
   * The ids of every box this job maintains right now, as the node reports them.
   *
   * Called on the scan timer, never on the mining path, so it may page the node. It may throw: the
   * source logs the failure and keeps what the last pass found. The box behind an id is read back
   * before anything is built, which is why ids are all that is returned — anything more would be
   * stale by then.
   */
  def discover(ctx: BlockchainContext, api: NodeApi, height: Int): Seq[String]

  /**
   * Whether `box` may be advanced in a block at `height`. Pure: a decision from the box's own
   * registers and the height, with no node read, because it is asked once per tracked box per
   * block.
   */
  def due(box: InputUTXO, height: Int): Boolean

  /**
   * The successor transaction for one due box, signed, or nothing when the box cannot be advanced.
   *
   * Signed by the job rather than the source because only the job knows what satisfies its
   * script: a box guarded by a condition that reduces to true takes an empty proof, which a prover
   * holding no secret produces. The outputs are created at `height`, the block they will land in,
   * and any revenue sits at `payTo`.
   */
  def build(ctx: BlockchainContext, box: InputUTXO, height: Int, payTo: Contract): Option[UpkeepJob.Built]
}

object UpkeepJob {

  /**
   * What a job hands back for one box: the signed successor and the outputs of it that are this
   * miner's revenue. Sized by the source, not the job, from the signed bytes and the cost signing
   * reported, so no job can understate what it costs the block.
   */
  final case class Built(tx: SignedTransaction, capital: Seq[CapitalEntry] = Seq.empty)
}
