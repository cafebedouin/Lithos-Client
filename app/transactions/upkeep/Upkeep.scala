package transactions.upkeep

import org.ergoplatform.appkit.SignedTransaction
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.{CandidateBudget, CandidateBundle, CapitalEntry}
import transactions.engine.execution.RollupExecution

/**
 * The pure half of the upkeep source: what a signed successor becomes on the candidate path, the
 * one rule the framework can check on a job's output, and how many successors fit a budget.
 *
 * Kept apart from the actor so a spec can drive each piece with values alone, the way the rent
 * source's sizing is driven: none of this needs a node, a timer or a mailbox.
 */
object Upkeep {

  /** Every upkeep candidate's `kind` starts with this; the job's name follows, so a refusal names it. */
  final val KindPrefix = "upkeep"

  def kind(job: String): String = s"$KindPrefix:$job"

  /**
   * One successor ready to be offered: the box it advances, the job that built it, and what the
   * block is charged for it. One bundle each, because successors are independent — a package that
   * cannot fit one of them should still take the others.
   */
  final case class Prepared(job: String, boxId: String, tx: CandidateTx, capital: Seq[CapitalEntry]) {
    def bundle: CandidateBundle = CandidateBundle(Vector(tx), capital = capital)
  }

  /**
   * A signed successor as the candidate path carries it, sized the way the rent source sizes its
   * merge: serialized bytes as the node counts them, and the cost signing measured.
   */
  def member(signed: SignedTransaction, job: String): CandidateTx =
    CandidateTx(signed.getId, CandidateTx.signedJson(signed), kind(job),
      RollupExecution.signedInputIds(signed), RollupExecution.signedSizeBytes(signed),
      signed.getCost.toLong, RollupExecution.signedLeaf(signed))

  /**
   * The inputs a job signed that it never discovered. Empty for an acceptable transaction.
   *
   * This is the one rule the framework can enforce on a job's output: a successor spending only
   * the boxes the job maintains cannot spend this miner's wallet, and cannot take anyone else's
   * box however its script happens to reduce.
   */
  def undiscoveredInputs(signed: SignedTransaction, discovered: Set[String]): Set[String] =
    RollupExecution.signedInputIds(signed) -- discovered

  /**
   * As many successors as the source's share affords, in the order they were prepared.
   *
   * One that does not fit is passed over rather than ending the fit: each is its own transaction,
   * so a large one over the remaining budget says nothing about a smaller one behind it. Two
   * spending the same box are a double spend the package would refuse whole, so the second is left
   * out here, where the first is still known.
   */
  def fitting(prepared: Seq[Prepared], maxTxs: Int, budget: CandidateBudget): Seq[Prepared] = {
    var bytes = 0L
    var cost = 0L
    var claimed = Set.empty[String]
    var chosen = Vector.empty[Prepared]
    prepared.foreach { candidate =>
      val inputs = candidate.tx.inputIds
      val fits = chosen.size < maxTxs &&
        bytes + candidate.tx.sizeBytes <= budget.maxBytes &&
        cost + candidate.tx.cost <= budget.maxCost &&
        !inputs.exists(claimed.contains)
      if (fits) {
        chosen :+= candidate
        bytes += candidate.tx.sizeBytes
        cost += candidate.tx.cost
        claimed ++= inputs
      }
    }
    chosen
  }
}
