package transactions.upkeep

import configs.UpkeepConfig

/**
 * Every upkeep job this client knows, in the order their work is offered to a block.
 *
 * A job is registered here and nowhere else: config can only turn a registered job on, so an
 * operator cannot be made to run maintenance that was never reviewed, and a name in config that
 * matches nothing is reported at startup rather than silently never run.
 */
object UpkeepRegistry {

  /** Empty until the first job lands; the registry exists so adding one is one line here. */
  val all: Seq[UpkeepJob] = Seq.empty

  def byName(name: String): Option[UpkeepJob] = all.find(_.name == name)

  /** The jobs config turns on, out of `jobs`. Off is the default for every one of them. */
  def enabled(config: UpkeepConfig, jobs: Seq[UpkeepJob] = all): Seq[UpkeepJob] =
    jobs.filter(job => config.jobEnabled(job.name))
}
