package transactions.upkeep

import org.ergoplatform.appkit.{NetworkType, Parameters}
import work.lithos.mutations.{Contract, InputUTXO, UTXO}

/**
 * The smallest [[ScriptJob]]: boxes at the always-true script, due when the spec says, and a
 * successor that recreates the box [[FakeScriptJob.Tip]] lighter and pays the tip to `payTo`.
 *
 * The always-true script is what lets the base class's keyless prover sign it offline, the same
 * way it signs a due-job box, so everything [[ScriptJob]] owns — discovery, assembly, signing, the
 * capital entries — is exercised without the heartbeat's own rule in the way.
 */
final class FakeScriptJob(boxIds: Seq[String] = Seq.empty, val name: String = "script") extends ScriptJob(boxIds) {

  import FakeScriptJob._

  /** Whether every box is due. */
  @volatile var isDue: Boolean = true

  /** Boxes at the script the job says it does not maintain. */
  @volatile var foreign: Set[String] = Set.empty

  /** Boxes the successor reads without spending. */
  @volatile var dataInputs: Seq[InputUTXO] = Seq.empty

  /** The plan's revenue indices; the tip output's when not set. */
  @volatile var revenue: Seq[Int] = Seq(1)

  /** Say the box cannot pay for its successor. */
  @volatile var cannotPay: Boolean = false

  override def contract(network: NetworkType): Contract = Contract.SIGMA_TRUE

  override def maintains(box: InputUTXO): Boolean = !foreign.contains(box.id.toString)

  override def due(box: InputUTXO, height: Int): Boolean = isDue

  override def plan(box: InputUTXO, bc: BuildContext): Option[Successor] =
    if (cannotPay) None
    else Some(Successor(
      outputs = Seq(
        UTXO(box.contract, box.value - Tip, box.tokens, box.registers).setCreationHeight(bc.height),
        UTXO(bc.payTo, Tip).setCreationHeight(bc.height)),
      dataInputs = dataInputs,
      revenue = revenue))
}

object FakeScriptJob {

  /** What one successor pays, in nanoERG: well over the dust minimum of a token-free box. */
  final val Tip: Long = Parameters.OneErg / 1000
}
