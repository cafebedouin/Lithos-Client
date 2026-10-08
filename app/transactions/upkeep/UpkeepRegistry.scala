package transactions.upkeep

import configs.UpkeepConfig
import transactions.upkeep.jobs.HeartbeatJob

/**
 * Every upkeep job this client knows, in the order their work is offered to a block.
 *
 * A job is registered here and nowhere else: config can only turn a registered job on, so an
 * operator cannot be made to run maintenance that was never reviewed, and a name in config that
 * matches nothing is reported at startup rather than silently never run.
 *
 * Jobs are built from config rather than held as values because one of them has settings of its
 * own; [[names]] is the same list without a config, for validation, which runs before any config
 * object exists and only needs to know what a name may be.
 */
object UpkeepRegistry {

  /** The names [[all]] answers to, in the same order. Validation checks config against this. */
  val names: Seq[String] = Seq(HeartbeatJob.Name)

  def all(config: UpkeepConfig): Seq[UpkeepJob] = Seq(new HeartbeatJob(config.heartbeat.boxIds))

  def byName(config: UpkeepConfig, name: String): Option[UpkeepJob] = all(config).find(_.name == name)

  /** The jobs config turns on, out of `jobs`. Off is the default for every one of them. */
  def enabled(config: UpkeepConfig, jobs: Seq[UpkeepJob]): Seq[UpkeepJob] =
    jobs.filter(job => config.jobEnabled(job.name))

  def enabled(config: UpkeepConfig): Seq[UpkeepJob] = enabled(config, all(config))
}
