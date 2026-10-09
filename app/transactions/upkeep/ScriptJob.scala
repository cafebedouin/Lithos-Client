package transactions.upkeep

import node.MutationConversions._
import node.NodeApi
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.{CapitalEntry, CapitalOrigin}
import transactions.rent.StorageRent
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import java.util.concurrent.ConcurrentHashMap
import scala.util.{Failure, Success, Try}

/**
 * What a job's rule produces for one due box: the outputs its successor transaction makes, the boxes
 * it reads without spending, and which of the outputs are this miner's.
 *
 * A plan rather than a transaction, so the job states only what its protocol decides and
 * [[ScriptJob]] does the assembly every such job would otherwise repeat. The outputs must spend the
 * box's value exactly: [[ScriptJob]] refuses a plan that leaves change, since the builder cannot
 * place sub-minimum change without a fee output and a block transaction carries none.
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
 * the box: the heartbeat, a Dexy tracker, an expiry refund. Such a job is its rule and nothing else:
 * the script, when a box is due, what the successor is, and which boxes go first.
 *
 * Everything around the rule is written once here, because each copy of it in a job would be one
 * more place to get wrong what the framework promises:
 *
 *  - Discovery. On a node with the extra index, every confirmed box at the script, soonest
 *    [[priority]] first. On every node, indexed or not, the ids listed under `jobs.<name>.boxIds`,
 *    each read from the UTXO set with one `boxById` call, so an unconfirmed box is never spent
 *    without its parent; the list is not a fallback, and its boxes come first and are never cut at
 *    the source's cap. A box is kept only if it sits at the script, its job [[maintains]] it, and
 *    EIP-27 does not block it, so a box anyone can create at a public script cannot take a place by
 *    being malformed.
 *  - Assembly with no fee and no wallet input: the one box as the only input, the plan's data inputs,
 *    and outputs that spend the box exactly.
 *  - Signing with a prover that holds no secret, so this miner's keys are never in reach of a job.
 *    The preHeader at signing carries only the block's height. A script that reads the miner's key,
 *    votes or the timestamp from it would sign here against placeholder values and be refused by the
 *    node, so such a protocol does not fit this shape.
 *
 * A job that does not fit — several protocol boxes in one transaction, a box found by token rather
 * than script — implements [[UpkeepJob]] directly.
 *
 * @param boxIds boxes the operator lists for this job, read on every discovery pass
 */
abstract class ScriptJob(boxIds: Seq[String]) extends UpkeepJob {

  private val listed: Seq[String] = boxIds.map(_.toLowerCase).distinct

  final override def configured: Set[String] = listed.toSet

  /**
   * The script every box of this job sits at. Asked on every discovery pass, so an implementation
   * should compile once per network, which [[ScriptJob.PerNetwork]] does, or pin the tree.
   */
  def contract(network: NetworkType): Contract

  /**
   * Whether a box at the script is one this job could ever advance: its registers are of the types
   * the script reads, and its terms make sense. Asked at discovery, so a malformed box is never held.
   */
  def maintains(box: InputUTXO): Boolean = true

  /**
   * The order discovery keeps boxes in, lowest first, so that when the source's cap cuts the list it
   * cuts the boxes that can wait. A job with a due height returns it.
   */
  def priority(box: InputUTXO): Long = 0L

  /**
   * The successor plan for a due box, or nothing when the box cannot be advanced, which the source
   * treats as the box being unable to pay. A throw is treated as a transient refusal.
   */
  def plan(box: InputUTXO, build: BuildContext): Option[Successor]

  /** Named after the job so a log line from a shared base still says whose box it is about. */
  private lazy val logger: Logger = LoggerFactory.getLogger(s"UpkeepJob.$name")

  final override def discover(ctx: BlockchainContext, api: NodeApi, height: Int): Seq[String] = {
    val network = ctx.getNetworkType
    val tree = contract(network).ergoTreeHex
    val indexed = if (api.indexerEnabled) ScriptJob.byTree(api, tree, name) else Seq.empty[NodeBox]
    val read = listed.flatMap { id =>
      api.boxById(id) match {
        case Success(box) => box
        case Failure(ex) =>
          throw new IllegalStateException(s"could not read configured $name box $id: ${ex.getMessage}", ex)
      }
    }
    // Parsed once, here: a register that does not decode makes the box as foreign as a wrong script.
    def ours(box: NodeBox): Option[Long] =
      if (box.ergoTree != tree) None
      else Try {
        val input = box.toInputUTXO(ctx)
        if (maintains(input) && !StorageRent.blockedByReEmission(input, network)) Some(priority(input)) else None
      }.toOption.flatten
    // A configured box that is not this job's is the operator's mistake, so it is named; an indexed
    // box that is not this job's is just someone else's.
    val kept = read.filter { box =>
      val mine = ours(box).isDefined
      if (!mine) logger.warn(s"Configured $name box ${box.boxId} is not one this job maintains; ignoring it")
      mine
    }.map(_.boxId)
    val found = indexed.flatMap(box => ours(box).map(box.boxId -> _)).sortBy(_._2).map(_._1)
    (kept ++ found).distinct
  }

  final override def build(box: InputUTXO, bc: BuildContext): Option[UpkeepJob.Built] =
    plan(box, bc).map(successor => ScriptJob.signed(name, box, successor, bc))
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
  private def signed(job: String, box: InputUTXO, successor: Successor, bc: BuildContext): UpkeepJob.Built = {
    require(successor.revenue.forall(i => i >= 0 && i < successor.outputs.size),
      s"$job's revenue ${successor.revenue.mkString(", ")} names an output the plan does not have " +
        s"(${successor.outputs.size} outputs)")
    val spent = successor.outputs.map(_.value).sum
    require(spent == box.value,
      s"$job's plan spends $spent of the box's ${box.value} nanoERG; it must spend the box exactly")
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
