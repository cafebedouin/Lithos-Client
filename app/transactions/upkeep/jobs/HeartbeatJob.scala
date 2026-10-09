package transactions.upkeep.jobs

import lfsm.contracts.UpkeepContracts
import org.ergoplatform.appkit.{ErgoValue, NetworkType}
import transactions.upkeep.{BuildContext, JobFactory, ScriptJob, Successor}
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

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
 * Discovery, assembly and signing are [[transactions.upkeep.ScriptJob]]'s, so this class is the
 * rule alone. Discovery by script needs the node's extra index (`ergo.node.extraIndex = true`); on a
 * plain node the job maintains the box ids the operator lists in `jobs.heartbeat.boxIds`, and that
 * list goes stale with every beat this or any other executor lands, since a beat gives the box a new
 * id and a plain node cannot be asked which transaction spent the old one.
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
 * Signing holds no secret: the script reduces to true for a well-formed successor, which is what
 * lets the base class sign it with a prover holding no key.
 */
final class HeartbeatJob(boxIds: Seq[String]) extends ScriptJob(boxIds) {

  import HeartbeatJob.Beat

  override val name: String = HeartbeatJob.Name

  override def contract(network: NetworkType): Contract = HeartbeatJob.contract(network)

  override def maintains(box: InputUTXO): Boolean = Beat.of(box).isDefined

  override def due(box: InputUTXO, height: Int): Boolean = Beat.of(box).exists(_.dueAt(height))

  override def plan(box: InputUTXO, bc: BuildContext): Option[Successor] =
    Beat.of(box).flatMap { beat =>
      // Sized at the full value, the most a successor could carry, so the floor is never understated.
      val successorFloor = ScriptJob.minimumValue(successor(box, beat, bc.height, box.value), bc)
      val tipOut = UTXO(bc.payTo, beat.tip).setCreationHeight(bc.height)
      val paysTip = beat.tip > 0L && beat.tip >= ScriptJob.minimumValue(tipOut, bc)
      val kept = if (paysTip) box.value - beat.tip else box.value
      if (kept < successorFloor) None
      else if (paysTip) Some(Successor(Seq(successor(box, beat, bc.height, kept), tipOut), revenue = Seq(1)))
      else Some(Successor(Seq(successor(box, beat, bc.height, kept))))
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

  private val compiled = new ScriptJob.PerNetwork(nt => UpkeepContracts.mkDueJobContract(nt))

  /** The contract, compiled once per network: discovery compares every box against its tree. */
  def contract(networkType: NetworkType): Contract = compiled(networkType)

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
  }
}
