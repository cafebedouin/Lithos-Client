package transactions.upkeep

import node.MutationConversions._
import node.NodeApi
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.{CapitalEntry, CapitalOrigin}
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import java.util.concurrent.ConcurrentHashMap
import scala.util.{Failure, Success, Try}

/**
 * What a job's rule produces for one due box: the outputs its successor transaction makes, the boxes
 * it reads without spending, and which of the outputs are this miner's.
 *
 * A plan rather than a transaction, so the job states only what its protocol decides and
 * [[ScriptJob]] does the assembly every such job would otherwise repeat. The outputs should spend
 * the box's value exactly: whatever they leave over goes to `payTo` as change, which is not declared
 * as capital and so is never aggregated.
 *
 * @param outputs    every output, in the order the protocol's script reads them
 * @param dataInputs boxes the script reads, such as an oracle; read by the job through the context
 *                   it is handed, never spent
 * @param revenue    indices into `outputs` of the ones paid to `payTo`, declared to the package as
 *                   capital its final top-up may aggregate
 */
final case class Successor(outputs: Seq[UTXO],
                           dataInputs: Seq[InputUTXO] = Seq.empty,
                           revenue: Seq[Int] = Seq.empty)

/**
 * An upkeep job whose boxes all sit at one known script, and whose successor is a fixed function of
 * the box: the shape of every keyless job foreseen so far — the heartbeat, a Dexy tracker, an expiry
 * refund. A job of this shape is its rule and nothing else: the script, when a box is due, and what
 * the successor is.
 *
 * Everything around the rule is here, written once, because every such job would otherwise carry its
 * own copy of it and each copy is a place to get wrong what the framework promises:
 *
 *  - Discovery by script on a node with the extra index, paged and confirmed only, plus the box ids
 *    the operator lists under `jobs.<name>.boxIds`, which is the only route a plain node offers.
 *    Both are taken when both apply. A box is kept only if it sits at the script and its job says it
 *    [[maintains]] it, so a box anyone can create at a public script cannot occupy the source's
 *    per-job cap by being malformed.
 *  - Assembly with no fee and no wallet input: the one box as the only input, the successor's data
 *    inputs, a preHeader at the block's height so `HEIGHT` reads as the block the successor lands
 *    in, and change, if any, to `payTo`.
 *  - Signing with a prover that holds no secret. A script that reduces to true for a well-formed
 *    successor takes the empty proof, which such a prover produces, so this miner's keys are never
 *    in reach of a job.
 *  - The capital entries for the outputs the plan names as revenue.
 *
 * What stays the job's, in [[plan]], is the check that the box can still pay for its successor:
 * only the job knows its outputs, and [[ScriptJob.minimumValue]] is there to size them.
 *
 * A job that does not fit this shape — several protocol boxes in one transaction, or a box found by
 * token rather than script — implements [[UpkeepJob]] directly instead.
 *
 * @param boxIds boxes to maintain on a node without the extra index, as read from config
 */
abstract class ScriptJob(boxIds: Seq[String]) extends UpkeepJob {

  /**
   * The script every box of this job sits at. Asked on every discovery pass, so an implementation
   * should compile once per network, which [[ScriptJob.PerNetwork]] does.
   */
  def contract(network: NetworkType): Contract

  /**
   * Whether a box at the script is one this job could ever advance: its registers are of the types
   * the script reads, and its terms make sense. Asked at discovery, so a malformed box is never
   * held. Every box at the script is maintained unless a job says otherwise.
   */
  def maintains(box: InputUTXO): Boolean = true

  /**
   * The successor plan for a due box, or nothing when the box cannot be advanced, which is how a
   * job says a box can no longer pay for its own successor. A throw is treated the same way.
   */
  def plan(box: InputUTXO, build: BuildContext): Option[Successor]

  /** Named after the job so a log line from a shared base still says whose box it is about. */
  private lazy val logger: Logger = LoggerFactory.getLogger(s"UpkeepJob.$name")

  final override def discover(ctx: BlockchainContext, api: NodeApi, height: Int): Seq[String] = {
    val tree = contract(ctx.getNetworkType).ergoTreeHex
    val indexed = if (api.indexerEnabled) ScriptJob.byTree(api, tree, name) else Seq.empty[NodeBox]
    val configured = if (boxIds.isEmpty) Seq.empty[NodeBox] else api.boxesWithPoolByIds(boxIds) match {
      case Success(boxes) => boxes
      case Failure(ex) =>
        throw new IllegalStateException(s"could not read the ${boxIds.size} configured $name boxes: ${ex.getMessage}", ex)
    }
    // Parsed once, here: a register that does not decode makes the box as foreign as a wrong script.
    def ours(box: NodeBox): Boolean =
      box.ergoTree == tree && Try(maintains(box.toInputUTXO(ctx))).getOrElse(false)
    // A configured box that is not this job's is the operator's mistake, and the one route to it on
    // a plain node, so it is named; an indexed box that is not this job's is just someone else's.
    val (kept, stray) = configured.partition(ours)
    stray.foreach(box => logger.warn(s"Configured $name box ${box.boxId} is not one this job maintains; ignoring it"))
    (indexed.filter(ours) ++ kept).map(_.boxId).distinct
  }

  final override def build(box: InputUTXO, bc: BuildContext): Option[UpkeepJob.Built] =
    plan(box, bc).map(successor => ScriptJob.signed(box, successor, bc))
}

object ScriptJob {

  /** Boxes per index page, and the pages one pass will read; the source keeps fewer boxes anyway. */
  private[upkeep] final val PageSize = 100
  private[upkeep] final val MaxPages = 10

  /**
   * A job's contract compiled once per network and then shared: discovery compares every box
   * against its tree on every pass, and compiling ErgoScript for each would be the dearest thing the
   * scan does.
   */
  final class PerNetwork(compile: NetworkType => Contract) {
    private val compiled = new ConcurrentHashMap[NetworkType, Contract]()

    def apply(network: NetworkType): Contract =
      compiled.computeIfAbsent(network, (nt: NetworkType) => compile(nt))
  }

  /**
   * The least value `out` may carry: the node's price per byte times the box's serialized length,
   * reference to its transaction included, which is what the node counts for the minimum.
   *
   * Here for a job's [[ScriptJob.plan]], since saying whether a box can still pay for its successor
   * is the job's and sizing a box is the same for every job.
   */
  def minimumValue(out: UTXO, bc: BuildContext): Long =
    bc.params.getMinValuePerByte.toLong *
      out.toInput(bc.ctx, ErgoId.create("00" * 32), 0.toShort).bytes.length.toLong

  /**
   * The plan assembled and signed: `box` the only input, no fee, the block's height in the
   * preHeader, an empty proof from a prover with no secret, and the revenue declared as capital.
   */
  private def signed(box: InputUTXO, successor: Successor, bc: BuildContext): UpkeepJob.Built = {
    require(successor.revenue.forall(i => i >= 0 && i < successor.outputs.size),
      s"revenue ${successor.revenue.mkString(", ")} names an output the plan does not have " +
        s"(${successor.outputs.size} outputs)")
    val unsigned = TxBuilder(bc.ctx)
      .setInputs(box)
      .setDataInputs(successor.dataInputs: _*)
      .setOutputs(successor.outputs: _*)
      // HEIGHT is the block the successor lands in, which is what a due rule is written against.
      .setPreHeader(bc.ctx.createPreHeader().height(bc.height).build())
      .buildTx(0L, bc.payTo.address(bc.ctx))
    val tx = bc.ctx.newProverBuilder().build().sign(unsigned)
    val capital = successor.revenue.distinct.map(i => CapitalEntry(CapitalOrigin.ExecutorReward,
      InputUTXO(tx.getOutputsToSpend.get(i)), parentTxId = tx.getId))
    UpkeepJob.Built(tx, capital)
  }

  /**
   * Every unspent box at `tree` the index reports, confirmed only. Throws on a failed page so the
   * source keeps its last pass rather than a partial one.
   */
  private def byTree(api: NodeApi, tree: String, job: String): Seq[NodeBox] = {
    var found = Vector.empty[NodeBox]
    var paging = Paging(0, PageSize)
    var pages = 0
    var exhausted = false
    while (!exhausted && pages < MaxPages) {
      val page = api.unspentBoxesByErgoTree(tree, paging, SortDirection.Asc, MempoolOptions.ConfirmedOnly) match {
        case Success(boxes) => boxes
        case Failure(ex) =>
          throw new IllegalStateException(s"the node index could not list $job boxes (page $pages): ${ex.getMessage}", ex)
      }
      found ++= page.map(_.box)
      exhausted = page.size < paging.limit
      paging = paging.next
      pages += 1
    }
    found
  }
}
