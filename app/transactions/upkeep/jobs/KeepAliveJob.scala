package transactions.upkeep.jobs

import node.MutationConversions._
import node.NodeApi
import node.model.{MempoolOptions, NodeBox, Paging, SortDirection}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{BlockchainContext, ContextVar, ErgoValue}
import org.slf4j.{Logger, LoggerFactory}
import scorex.crypto.hash.Blake2b256
import sigma.ast.ErgoTree
import transactions.candidate.{CapitalEntry, CapitalOrigin}
import transactions.upkeep.{BuildContext, JobFactory, ScriptJob, UpkeepJob}
import work.lithos.mutations.{InputUTXO, Token, TxBuilder, UTXO}

import scala.util.{Failure, Success, Try}

/**
 * Keeps KeepAlive receive addresses alive: merges the boxes paid to one address into one, and
 * refreshes a lone box before it reaches storage-rent age, for the bounty the address's script pays.
 *
 * A KeepAlive address (`test/resources/upkeep/KeepAliveAddress.ergo`, skunkyard `skunks/keepalive`)
 * is a script with its owner's key as a constant, so a payer pays it like any address. Its keyless
 * path lets anyone merge every box at the address into one output holding every token and the
 * total value less a bounty: at most [[Terms.perInput]] per box and never more than the boxes other
 * than the largest bring, so the largest never pays for a merge. A lone box may be refreshed, for at
 * most [[Terms.refresh]], only within [[Terms.window]] blocks of [[Terms.period]]. The new box's
 * creation height restarts its rent clock.
 *
 * Every owner's address is the same script with a different key, so they share one template, and
 * discovery asks the node's index for every unspent box under [[TemplateHash]] (the index hashes
 * the template with Blake2b256). The terms are read from each box's own tree, not from config, since
 * the script enforces the tree's terms whatever this job believes; a tree whose terms do not parse,
 * or make no sense, is passed over.
 *
 * Due: of each address's boxes, only the largest (ties by id) is due, and only when there is
 * something to do: another box to merge, or the lone box in its refresh window. The others are
 * reported so the source reads them back, which is what lets the merge spend them; a box the source
 * did not read back this pass makes the source refuse the whole merge, and the next pass tries again.
 *
 * The successor carries no registers, every input names output 0 in context variable 0, and the
 * bounty, when it is worth a box of its own, is paid to this miner's collection contract; otherwise
 * it stays in the merged box and the merge is made for free, which the script allows.
 *
 * @param minBounty the least bounty a transaction must pay to be built, in nanoERG; 0 merges for free
 * @param maxInputs the most boxes one merge spends; the script's cost grows with the square of it
 */
final class KeepAliveJob(minBounty: Long = KeepAliveJob.DefaultMinBounty,
                         maxInputs: Int = KeepAliveJob.DefaultMaxInputs) extends UpkeepJob {

  import KeepAliveJob._

  override val name: String = Name

  private lazy val logger: Logger = LoggerFactory.getLogger(s"UpkeepJob.$name")

  /** Every box seen on the last pass, by address (tree), largest first; what a merge spends. */
  @volatile private var byAddress: Map[String, Seq[NodeBox]] = Map.empty

  override def discover(ctx: BlockchainContext, api: NodeApi, height: Int): Seq[String] = {
    if (!api.indexerEnabled) {
      logger.warn("KeepAlive discovery needs the node's extra index; no boxes this pass")
      byAddress = Map.empty
      return Seq.empty
    }
    var found = Vector.empty[NodeBox]
    var paging = Paging(0, ScriptJob.PageSize)
    var pages = 0
    var exhausted = false
    while (!exhausted && pages < MaxPages) {
      val page = api.unspentBoxesByTemplateHash(TemplateHash, paging, SortDirection.Desc,
        MempoolOptions.ConfirmedOnly) match {
        case Success(boxes) => boxes
        case Failure(ex) =>
          throw new IllegalStateException(s"the node index could not list KeepAlive boxes (page $pages): ${ex.getMessage}", ex)
      }
      found ++= page.map(_.box)
      exhausted = page.size < paging.limit
      paging = paging.next
      pages += 1
    }
    val ours = found.filter(b => Terms.of(b.ergoTree).isDefined)
    byAddress = ours.groupBy(_.ergoTree).map { case (tree, boxes) => tree -> boxes.sortBy(b => (-b.value, b.boxId)) }
    // Each address's boxes up to maxInputs, largest first: the merge spends exactly these
    byAddress.values.toSeq.flatMap(_.take(maxInputs).map(_.boxId))
  }

  override def due(box: InputUTXO, height: Int): Boolean = {
    val tree = box.contract.ergoTreeHex
    Terms.of(tree).exists { terms =>
      byAddress.get(tree).exists { boxes =>
        boxes.headOption.exists(_.boxId == box.id.toString) &&
          (boxes.size >= 2 || height >= box.input.getCreationHeight + terms.period - terms.window)
      }
    }
  }

  override def build(box: InputUTXO, bc: BuildContext): Option[UpkeepJob.Built] = {
    val tree = box.contract.ergoTreeHex
    val terms = Terms.of(tree).getOrElse(throw new IllegalArgumentException(s"box ${box.id} is not a KeepAlive address"))
    val spent: Seq[InputUTXO] = byAddress.getOrElse(tree, Seq.empty).take(maxInputs).map { b =>
      if (b.boxId == box.id.toString) box else b.toInputUTXO(bc.ctx)
    } match {
      case s if s.exists(_.id == box.id) => s
      case s => box +: s
    }
    val total = spent.map(_.value).sum
    val largest = spent.map(_.value).max
    val merge = spent.size >= 2
    val allowed =
      if (merge) math.min(spent.size.toLong * terms.perInput, total - largest)
      else math.min(box.value / 2, terms.refresh)
    val tokens = spent.flatMap(_.tokens).groupBy(_.id.toString).toSeq.sortBy(_._1)
      .map { case (id, ts) => Token(id, ts.map(_.amount).sum) }
    def successor(value: Long): UTXO = UTXO(box.contract, value, tokens).setCreationHeight(bc.height)
    // The bounty pays this miner only when it is worth a box; otherwise the merge is free
    val bountyOut = UTXO(bc.payTo, allowed).setCreationHeight(bc.height)
    val paid = if (allowed > 0L && allowed >= ScriptJob.minimumValue(bountyOut, bc)) allowed else 0L
    if (paid < minBounty) return None
    if (total - paid < ScriptJob.minimumValue(successor(total - paid), bc)) return None
    val outputs = if (paid > 0L) Seq(successor(total - paid), bountyOut) else Seq(successor(total))
    val inputs = spent.map(_.setCtxVars(ContextVar.of(0.toByte, ErgoValue.of(0))))
    val unsigned = TxBuilder(bc.ctx)
      .setInputs(inputs: _*)
      .setOutputs(outputs: _*)
      .setPreHeader(bc.ctx.createPreHeader().height(bc.height).build())
      .buildTx(0L, bc.payTo.address(bc.ctx))
    val tx = bc.ctx.newProverBuilder().build().sign(unsigned)
    val capital = if (paid > 0L) Seq(CapitalEntry(CapitalOrigin.ExecutorReward,
      InputUTXO(tx.getOutputsToSpend.get(1)), parentTxId = tx.getId)) else Seq.empty
    Some(UpkeepJob.Built(tx, capital))
  }
}

object KeepAliveJob {

  final val Name = "keepalive"

  final val MinBountyKey = "minBounty"
  final val MaxInputsKey = "maxInputs"

  /** A merge or refresh must pay at least this to be built: 0.0005 ERG, the script's per-box bounty. */
  final val DefaultMinBounty: Long = 500000L

  /** 50 boxes a merge; checked on a devnet node well inside a block's cost. */
  final val DefaultMaxInputs: Int = 50

  /** Index pages per pass, as a script job reads. */
  private final val MaxPages = 10

  /**
   * Blake2b256 of the KeepAlive address template (the tree without its constants), as the node's
   * index hashes it. The same for every owner and for any terms: they are all constants.
   */
  final val TemplateHash: String = "afc009dcb6d619a42be622b6b950651dfefa8409381abdb8bb27af8162965c19"

  val Factory: JobFactory = JobFactory(Name,
    job => new KeepAliveJob(job.block.getOptional[Long](MinBountyKey).getOrElse(DefaultMinBounty),
      job.block.getOptional[Int](MaxInputsKey).getOrElse(DefaultMaxInputs)),
    check = block => {
      val bounty = block.getOptional[Long](MinBountyKey) match {
        case Some(b) if b < 0L => Seq(MinBountyKey -> "must not be negative")
        case _ => Seq.empty
      }
      val inputs = block.getOptional[Int](MaxInputsKey) match {
        case Some(n) if n < 2 || n > 200 => Seq(MaxInputsKey -> "must be between 2 and 200")
        case _ => Seq.empty
      }
      bounty ++ inputs
    })

  /**
   * One address's terms, read from its tree's constants at the positions the template puts them:
   * the owner's key (1), PERIOD (4), WINDOW (5), PER_INPUT (7), REFRESH (10), SLACK (11).
   */
  final case class Terms(owner: String, period: Int, window: Int, perInput: Long, refresh: Long, slack: Int)

  object Terms {
    def of(treeHex: String): Option[Terms] = Try {
      val tree = ErgoTree.fromHex(treeHex)
      if (Hex.toHexString(Blake2b256.hash(tree.template)) != TemplateHash) None
      else {
        val c = tree.constants.map(_.value)
        val owner = c(1) match {
          case bytes: sigma.Coll[_] => Hex.toHexString(bytes.toArray.asInstanceOf[Array[Byte]])
          case other => throw new IllegalArgumentException(s"owner is $other")
        }
        val t = Terms(owner, c(4).asInstanceOf[Int], c(5).asInstanceOf[Int], c(7).asInstanceOf[Long],
          c(10).asInstanceOf[Long], c(11).asInstanceOf[Int])
        if (t.period > t.window && t.window > 0 && t.perInput >= 0L && t.refresh >= 0L && t.slack >= 0) Some(t) else None
      }
    }.toOption.flatten
  }
}
