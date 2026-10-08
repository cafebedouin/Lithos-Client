package transactions.upkeep.jobs

import lfsm.contracts.UpkeepContracts
import node.MutationConversions._
import node.NodeApi
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, NetworkType}
import org.ergoplatform.sdk.ErgoId
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.{CapitalEntry, CapitalOrigin}
import transactions.upkeep.UpkeepJob
import work.lithos.mutations.{Contract, InputUTXO, TxBuilder, UTXO}

import java.util.concurrent.ConcurrentHashMap
import scala.util.{Failure, Success, Try}

/**
 * The reference upkeep job: advances boxes under `upkeep/DueJob.ergo`, which state their own
 * successor.
 *
 * A due-job box says in its registers when it is next due (R4 last beat, R5 period) and what it
 * pays (R6 tip). Its script lets anyone recreate it once due, stamped with the block height and
 * lighter by at most the tip. That is everything this job needs to know, so it holds no protocol
 * knowledge at all: discovery is "every box at that script", due is the register rule, and the
 * successor is the box with R4 rewritten.
 *
 * Discovery needs the node's extra index (`ergo.node.extraIndex = true`): boxes by script is an
 * index query, and so is boxes by token, so a plain node offers no cheaper route. On a plain node
 * the job falls back to the box ids the operator configured, read back in one call, which is
 * honest about what such a node can do: a beat gives the box a new id, and the plain node cannot
 * be asked which transaction spent the old one, so the list goes stale with every beat this or
 * any other executor lands. Both routes are taken when both apply.
 *
 * Only confirmed boxes are offered, and a beat someone else has in the mempool does not hold a
 * box back: the script pins the successor's R4 to the height it executes at, so a mempool beat is
 * valid in exactly one block and is most likely stale by the time this miner's block is built.
 * Advancing it in-block is what the script is designed for.
 *
 * A box cannot pay its beat once the successor keeping `value - tip` would fall under the
 * consensus minimum for a box of its size. The job says so by building nothing, which the source
 * remembers, and the box ages out through storage rent as its contract intends. A tip too small
 * for a box of its own is left in the successor instead: the beat is then made for free, which
 * keeps the box alive and is the point of a heartbeat.
 *
 * Signing holds no secret. The script reduces to true for a well-formed successor, and a prover
 * built with no key produces the empty proof such a script takes, so this miner's wallet is never
 * touched.
 */
final class HeartbeatJob(boxIds: Seq[String]) extends UpkeepJob {

  import HeartbeatJob._

  override val name: String = Name

  override def discover(ctx: BlockchainContext, api: NodeApi, height: Int): Seq[String] = {
    val tree = contract(ctx.getNetworkType).ergoTreeHex
    val indexed = if (api.indexerEnabled) byTree(api, tree) else Seq.empty[NodeBox]
    val configured = if (boxIds.isEmpty) Seq.empty[NodeBox] else api.boxesWithPoolByIds(boxIds) match {
      case Success(boxes) => boxes
      case Failure(ex) =>
        throw new IllegalStateException(s"could not read the ${boxIds.size} configured heartbeat boxes: ${ex.getMessage}", ex)
    }
    // A configured box that is not a due-job box is the operator's mistake, and the one route to it
    // on a plain node, so it is named; an indexed box that fails to parse is just not this job's.
    configured.filterNot(box => maintained(box, tree)).foreach(box =>
      logger.warn(s"Configured heartbeat box ${box.boxId} is not a well-formed due-job box; ignoring it"))
    (indexed ++ configured).filter(box => maintained(box, tree)).map(_.boxId).distinct
  }

  override def due(box: InputUTXO, height: Int): Boolean = Beat.of(box).exists(_.dueAt(height))

  override def build(ctx: BlockchainContext, box: InputUTXO, height: Int, payTo: Contract): Option[UpkeepJob.Built] =
    Beat.of(box).flatMap { beat =>
      val perByte = ctx.getDataSource.getParameters.getMinValuePerByte.toLong
      // Sized at the full value, the most a successor could carry, so the floor is never understated.
      val successorFloor = perByte * sizeOf(ctx, successor(box, beat, height, box.value))
      val paysTip = beat.tip > 0L &&
        beat.tip >= perByte * sizeOf(ctx, UTXO(payTo, beat.tip).setCreationHeight(height))
      val kept = if (paysTip) box.value - beat.tip else box.value
      if (kept < successorFloor) None
      else {
        val outputs = successor(box, beat, height, kept) +:
          (if (paysTip) Seq(UTXO(payTo, beat.tip).setCreationHeight(height)) else Seq.empty[UTXO])
        val unsigned = TxBuilder(ctx)
          .setInputs(box)
          .setOutputs(outputs: _*)
          // HEIGHT is the block the beat lands in: the script stamps R4 with it and is due against it.
          .setPreHeader(ctx.createPreHeader().height(height).build())
          .buildTx(0L, payTo.address(ctx))
        val signed = ctx.newProverBuilder().build().sign(unsigned)
        val capital =
          if (paysTip) Seq(CapitalEntry(CapitalOrigin.ExecutorReward,
            InputUTXO(signed.getOutputsToSpend.get(1)), parentTxId = signed.getId))
          else Seq.empty[CapitalEntry]
        Some(UpkeepJob.Built(signed, capital))
      }
    }

  /** The box as its script demands it back: same script and tokens, R4 at `height`, R5 and R6 kept. */
  private def successor(box: InputUTXO, beat: Beat, height: Int, value: Long): UTXO =
    UTXO(box.contract, value, box.tokens,
      Seq(ErgoValue.of(height), ErgoValue.of(beat.period), ErgoValue.of(beat.tip)))
      .setCreationHeight(height)

  /** Serialized bytes of `out` as the node counts them for the minimum value, reference included. */
  private def sizeOf(ctx: BlockchainContext, out: UTXO): Long =
    out.toInput(ctx, ErgoId.create("00" * 32), 0.toShort).bytes.length.toLong

  private def maintained(box: NodeBox, tree: String): Boolean =
    box.ergoTree == tree && Beat.of(box).isDefined
}

object HeartbeatJob {

  final val Name = "heartbeat"

  /** Boxes per index page, and the pages one pass will read; the source keeps fewer boxes anyway. */
  private final val PageSize = 100
  private final val MaxPages = 10

  private val logger: Logger = LoggerFactory.getLogger("UpkeepJob.heartbeat")

  private val compiled = new ConcurrentHashMap[NetworkType, Contract]()

  /** The contract, compiled once per network: discovery compares every box against its tree. */
  def contract(networkType: NetworkType): Contract =
    compiled.computeIfAbsent(networkType, (nt: NetworkType) => UpkeepContracts.mkDueJobContract(nt))

  /**
   * Every unspent box at `tree` the index reports, confirmed only. Throws on a failed page so the
   * source keeps its last pass rather than a partial one.
   */
  private def byTree(api: NodeApi, tree: String): Seq[NodeBox] = {
    var found = Vector.empty[NodeBox]
    var paging = Paging(0, PageSize)
    var pages = 0
    var exhausted = false
    while (!exhausted && pages < MaxPages) {
      val page = api.unspentBoxesByErgoTree(tree, paging, SortDirection.Asc, MempoolOptions.ConfirmedOnly) match {
        case Success(boxes) => boxes
        case Failure(ex) =>
          throw new IllegalStateException(s"the node index could not list due-job boxes (page $pages): ${ex.getMessage}", ex)
      }
      found ++= page.map(_.box)
      exhausted = page.size < paging.limit
      paging = paging.next
      pages += 1
    }
    found
  }

  /**
   * A due-job box's registers, read. Only R4 Int, R5 Int and R6 Long count; a box with anything
   * else there is not one of these and is passed over, since its script would refuse the spend
   * (`R4[Int].get` on a Long fails) and a period of zero or less is due every block, which no
   * heartbeat means.
   */
  final case class Beat(lastBeat: Int, period: Int, tip: Long) {
    def dueAt(height: Int): Boolean = height.toLong >= lastBeat.toLong + period.toLong
  }

  object Beat {

    def of(registers: Seq[ErgoValue[_]]): Option[Beat] = registers match {
      case Seq(r4, r5, r6, _*) => (r4.getValue, r5.getValue, r6.getValue) match {
        case (last: java.lang.Integer, period: java.lang.Integer, tip: java.lang.Long)
          if period > 0 && tip >= 0L => Some(Beat(last, period, tip))
        case _ => None
      }
      case _ => None
    }

    def of(box: InputUTXO): Option[Beat] = of(box.registers)

    /** As the node reports it; a register that does not decode is as malformed as a wrong type. */
    def of(box: NodeBox): Option[Beat] = Try(box.registerValues).toOption.flatMap(regs => of(regs))
  }
}
