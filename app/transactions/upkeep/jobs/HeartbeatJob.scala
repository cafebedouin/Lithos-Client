package transactions.upkeep.jobs

import lfsm.contracts.UpkeepContracts
import org.ergoplatform.appkit.{ErgoValue, NetworkType}
import sigma.ast.ErgoTree
import transactions.upkeep.{BuildContext, JobFactory, ScriptJob, Successor}
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

/**
 * The reference upkeep job: advances boxes under `upkeep/DueJob.ergo`, which state their own
 * successor.
 *
 * A due-job box says in its registers when it is next due (R4 last beat, R5 period) and what it
 * pays (R6 tip). Its script lets anyone recreate it once due, stamped with the block height and
 * lighter by at most the tip, so this job holds no protocol knowledge: discovery is "every box at
 * that tree", due is the register rule, and the successor is the box with R4 rewritten.
 *
 * The tree is pinned, not compiled: [[HeartbeatJob.TreeHex]] is what was reviewed, and a change to
 * the script file cannot change which boxes a running client spends. The spec compiles the script
 * and holds it to the constant.
 *
 * Discovery by script needs the node's extra index; on a plain node the job maintains the box ids
 * listed in `jobs.heartbeat.boxIds`, which go stale with every beat, since a beat gives the box a
 * new id. The source reads each box back through the node's mempool-adjusted view, so a box a
 * pending transaction already spends does not come back and is skipped for that block.
 *
 * The beat pays what the box can spare: the tip, or less when paying it in full would leave the
 * successor under the consensus minimum. A payment too small for a box of its own is left in the
 * successor and the beat is made for free, which the script allows and which keeps the box alive.
 */
final class HeartbeatJob(boxIds: Seq[String]) extends ScriptJob(boxIds) {

  import HeartbeatJob.Beat

  override val name: String = HeartbeatJob.Name

  override def contract(network: NetworkType): Contract = HeartbeatJob.contract(network)

  override def maintains(box: InputUTXO): Boolean = Beat.of(box).isDefined

  override def due(box: InputUTXO, height: Int): Boolean = Beat.of(box).exists(_.dueAt(height))

  /** The height the box is due at, so the source's cap keeps the soonest due. */
  override def priority(box: InputUTXO): Long = Beat.of(box).map(_.dueHeight).getOrElse(Long.MaxValue)

  /**
   * The R6 tip: the most a beat pays. A box that cannot spare it all pays less, so the tip is an
   * upper bound, which is all the source's ranking asks for; a box ranked high on a tip it cannot
   * pay costs one build, not a block's share.
   */
  override def expectedRevenue(box: InputUTXO): Long = Beat.of(box).map(_.tip).getOrElse(0L)

  override def plan(box: InputUTXO, bc: BuildContext): Option[Successor] =
    Beat.of(box).map { beat =>
      // Sized at the full value, the most a successor could carry, so the floor is never understated.
      val successorFloor = ScriptJob.minimumValue(successor(box, beat, bc.height, box.value), bc)
      val paid = math.max(0L, math.min(beat.tip, box.value - successorFloor))
      val tipOut = UTXO(bc.payTo, paid).setCreationHeight(bc.height)
      if (paid > 0L && paid >= ScriptJob.minimumValue(tipOut, bc))
        Successor(Seq(successor(box, beat, bc.height, box.value - paid), tipOut), revenue = Seq(1))
      else Successor(Seq(successor(box, beat, bc.height, box.value)))
    }

  /** The box as its script demands it back: same script and tokens, R4 at `height`, R5 and R6 kept. */
  private def successor(box: InputUTXO, beat: Beat, height: Int, value: Long): UTXO =
    UTXO(box.contract, value, box.tokens,
      Seq(ErgoValue.of(height), ErgoValue.of(beat.period), ErgoValue.of(beat.tip)))
      .setCreationHeight(height)
}

object HeartbeatJob {

  final val Name = "heartbeat"

  /** Made from the keys every job carries: the heartbeat has none of its own. */
  val Factory: JobFactory = JobFactory(Name, job => new HeartbeatJob(job.boxIds))

  /**
   * The ErgoTree of `upkeep/DueJob.ergo` as reviewed. The script takes no constants and names no
   * address, so the tree is the same on every network; `HeartbeatJobSpec` compiles it for mainnet
   * and testnet and holds both to this.
   */
  final val TreeHex: String =
    "1b8f01040400040005000400d804d601e4c6a70504d602e4c6a70605d603b2a5730000d604c1a7d1edededededed" +
      "edededed9172017301927202730293c5b2a4730300c5a7927ea3059a7ee4c6a70404057e72010593c27203c2a793" +
      "db63087203db6308a793e4c672030404a3938cc7720301a393e4c672030504720193e4c672030605720292c17203" +
      "997204a172027204"

  private val pinned: Contract = Contract(ErgoTree.fromHex(TreeHex))

  /** The pinned contract, whatever the network: discovery compares every box against its tree. */
  def contract(networkType: NetworkType): Contract = pinned

  /** The script as it compiles now, for the spec that holds it to [[TreeHex]]. */
  def compile(networkType: NetworkType): Contract = UpkeepContracts.mkDueJobContract(networkType)

  /**
   * A due-job box's registers, read: R4 Int, R5 Int and R6 Long, with a positive period and a tip
   * not below zero. Anything else is passed over, because the script refuses to spend it.
   */
  final case class Beat(lastBeat: Int, period: Int, tip: Long) {
    def dueHeight: Long = lastBeat.toLong + period.toLong

    def dueAt(height: Int): Boolean = height.toLong >= dueHeight
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
  }
}
