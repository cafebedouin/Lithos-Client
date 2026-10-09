package transactions.upkeep

import org.ergoplatform.appkit.impl.SignedTransactionImpl
import org.ergoplatform.appkit.{BlockchainParameters, SignedTransaction}
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.{CandidateBudget, CandidateBundle, CapitalEntry}
import transactions.engine.execution.RollupExecution
import work.lithos.mutations.InputUTXO

/**
 * The pure half of the upkeep source: what a successor costs the block before and after it is
 * built, what a signed successor becomes on the candidate path, the one rule the framework can
 * check on a job's output, and what is left of the source's share as successors are admitted.
 *
 * Kept apart from the actor so a spec can drive each piece with values alone, the way the rent
 * source's sizing is driven: none of this needs a node, a timer or a mailbox.
 */
object Upkeep {

  /** An upkeep candidate's `kind`: [[CandidateTx.Upkeep]] and the job's name, so a refusal names the job. */
  def kind(job: String): String = s"${CandidateTx.Upkeep}:$job"

  // ─── sizing ───────────────────────────────────────────────────────────────

  /**
   * What the node charges a transaction before any script runs, read from the interpreter the node
   * validates with rather than restated, so a change to it there is a change here. Signing starts
   * from the same figure and adds each input's reduction to it.
   */
  final val InitCost: Long = ErgoInterpreter.interpreterInitCost.toLong

  /**
   * The fewest bytes an input can take: a 32-byte box id, an empty proof's length byte, and an
   * empty extension. A keyless successor spends with exactly this; a signed one spends with more,
   * and the measured size says so once it is built. It is also the length of the reference a
   * serialized box carries to the transaction that made it, which is what makes [[floor]] simple.
   */
  final val KeylessInputBytes = 34L

  /**
   * The node's own accounting for a transaction of this shape, before any script runs: the
   * per-transaction, per-input, per-data-input and per-output cost, and the token term.
   *
   * `assets` is the token entries the transaction carries, the inputs' and the outputs' together,
   * each counted once. The factor of two on it is the node's, not a second count: the node charges
   * `tokenAccessCost` once for every entry and once more for every distinct token id, on each side
   * (`ErgoBoxAssetExtractor.totalAssetsAccessCost`). The distinct ids are not known here, and there
   * are never more of them than entries, so they are charged as one per entry. Exact for a
   * token-free transaction and for one whose every entry is a different token, and over otherwise,
   * which only costs package space.
   */
  def accountedCost(inputs: Int, dataInputs: Int, outputs: Int, assets: Int,
                    params: BlockchainParameters): Long =
    InitCost + inputs * params.getInputCost.toLong + dataInputs * params.getDataInputCost.toLong +
      outputs * params.getOutputCost.toLong + 2L * assets * params.getTokenAccessCost.toLong

  /**
   * What a successor of `box` adds to the block at the least, sized before it is built: in bytes,
   * the box recreated and the input that spends it with no proof; in cost, the node's accounting
   * for that shape, one input and one output, with the box's tokens carried through once: its
   * entries going in and the same entries coming out, which is the `2 * tokens` handed on as the
   * transaction's token entries.
   *
   * The bytes are the box's own serialized length. That carries a [[KeylessInputBytes]]-long
   * reference to the transaction that made the box, which the recreation drops and the proof-less
   * input weighs exactly, so the two cancel to within the transaction's own count bytes.
   *
   * A floor rather than an estimate, because nothing is known about the job's outputs or its
   * script until it builds. It is enough for what it decides: a box whose floor does not fit what
   * is left of the share cannot fit once built either, so it is never signed for that block.
   */
  def floor(box: InputUTXO, params: BlockchainParameters): (Long, Long) =
    (box.bytes.length.toLong,
      accountedCost(inputs = 1, dataInputs = 0, outputs = 1, assets = box.tokens.size + box.tokens.size, params))

  // ─── the candidate path ───────────────────────────────────────────────────

  /**
   * One successor ready to be offered: the box it advances, the job that built it, and what the
   * block is charged for it. One bundle each, because successors are independent — a package that
   * cannot fit one of them should still take the others.
   */
  final case class Prepared(job: String, boxId: String, tx: CandidateTx, capital: Seq[CapitalEntry]) {
    def bundle: CandidateBundle = CandidateBundle(Vector(tx), capital = capital)

    /** `job:box`, the way a log line names a successor. */
    def label: String = s"$job:${boxId.take(8)}"
  }

  /**
   * A signed successor as the candidate path carries it: serialized bytes as the node counts them,
   * and the cost signing measured, held at no less than the node's own accounting for the shape.
   *
   * Signing reports the whole figure the node will charge — its accounting for the inputs,
   * outputs and tokens, then every input's reduction — so the measured cost is the real one. The
   * accounting is computed again here from the parameters so that a cost reported under it, by a
   * prover that measured nothing, is never fitted at less than the node will charge: fitting on
   * such a number is how a package ends up over the block's limit. `box` is the input the job
   * advanced, whose tokens the accounting counts going in.
   */
  def member(signed: SignedTransaction, job: String, box: InputUTXO, params: BlockchainParameters): CandidateTx = {
    val tx = signed.asInstanceOf[SignedTransactionImpl].getTx
    val accounted = accountedCost(tx.inputs.size, tx.dataInputs.size, tx.outputs.size,
      box.tokens.size + tx.outputs.map(_.additionalTokens.length).sum, params)
    CandidateTx(signed.getId, CandidateTx.signedJson(signed), kind(job),
      RollupExecution.signedInputIds(signed), RollupExecution.signedSizeBytes(signed),
      math.max(signed.getCost.toLong, accounted), RollupExecution.signedLeaf(signed))
  }

  /**
   * The inputs a job signed that it never discovered. Empty for an acceptable transaction.
   *
   * This is the one rule the framework can enforce on a job's output: a successor spending only
   * the boxes the job maintains cannot spend this miner's wallet, and cannot take anyone else's
   * box however its script happens to reduce.
   */
  def undiscoveredInputs(signed: SignedTransaction, discovered: Set[String]): Set[String] =
    RollupExecution.signedInputIds(signed) -- discovered

  // ─── fitting ──────────────────────────────────────────────────────────────

  /**
   * What is left of the source's share for one block, and what has been admitted into it.
   *
   * Immutable, so the build can ask whether a box is worth signing before it signs it, and so a
   * spec can drive the fit with values alone. `slots`, `bytes` and `cost` are what remain; a
   * successor that does not fit is passed over rather than ending the fit, because each is its own
   * transaction and a large one over the remainder says nothing about a smaller one behind it. Two
   * spending the same box are a double spend the package would refuse whole, so the second is left
   * out here, where the first is still known.
   */
  final case class Share(slots: Int, bytes: Long, cost: Long,
                         claimed: Set[String] = Set.empty, chosen: Vector[Prepared] = Vector.empty) {

    /** Nothing more can be admitted, whatever it costs: no building is worth doing for this block. */
    def full: Boolean = slots <= 0 || bytes <= 0L || cost <= 0L

    /** Whether something of this size still has room, before its inputs are known. */
    def affords(sizeBytes: Long, sizeCost: Long): Boolean =
      slots > 0 && sizeBytes <= bytes && sizeCost <= cost

    /** The share with `candidate` admitted, or nothing when it does not fit or re-spends a box. */
    def admit(candidate: Prepared): Option[Share] = {
      val inputs = candidate.tx.inputIds
      if (!affords(candidate.tx.sizeBytes.toLong, candidate.tx.cost) || inputs.exists(claimed.contains)) None
      else Some(copy(slots - 1, bytes - candidate.tx.sizeBytes, cost - candidate.tx.cost,
        claimed ++ inputs, chosen :+ candidate))
    }

    def usedBytes(budget: CandidateBudget): Long = budget.maxBytes - bytes

    def usedCost(budget: CandidateBudget): Long = budget.maxCost - cost
  }

  object Share {
    def of(maxTxs: Int, budget: CandidateBudget): Share = Share(maxTxs, budget.maxBytes, budget.maxCost)
  }

}
