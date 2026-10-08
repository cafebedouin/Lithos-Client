package lfsm.contracts

import lfsm.ScriptGenerator
import org.ergoplatform.appkit.{BlockchainContext, ConstantsBuilder, NetworkType}
import work.lithos.mutations.Contract

/**
 * The contracts the upkeep source's jobs advance.
 *
 * `DueJob` takes no constants: a box under it is found by its tree alone, so the tree has to be the
 * same for every deployment, or a job would need a configured list of trees to look for.
 */
object UpkeepContracts {

  def mkDueJobContract(ctx: BlockchainContext): Contract = mkDueJobContract(ctx.getNetworkType)

  def mkDueJobContract(networkType: NetworkType): Contract =
    Contract.fromErgoScript(networkType, ConstantsBuilder.empty(), ScriptGenerator.mkUpkeepScript("DueJob"), Seq.empty)
}
