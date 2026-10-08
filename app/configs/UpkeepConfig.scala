package configs

import play.api.{ConfigLoader, Configuration}

/**
 * How this client looks for the boxes its upkeep jobs maintain, and which jobs it runs.
 *
 * Jobs are read generically from `jobs.<name>.enabled`, so registering a new job needs no config
 * code: the registry decides what a name means, and config only says whether it runs. A name that
 * is not in the registry is caught by validation, not here.
 *
 * @param scanIntervalMs gap between discovery passes. Discovery is background work and must never
 *                       be on the path a block is built on.
 * @param maxBoxesPerJob box ids one job may hold between passes. A job that finds more keeps the
 *                       first this many, so one busy protocol cannot grow the actor's memory or the
 *                       build's node read without bound.
 * @param jobs           each configured job name and whether it is on. Absent is off.
 * @param heartbeat      what the heartbeat job needs beyond its flag. The one job with settings of
 *                       its own so far; a job that needs none is just its flag in `jobs`.
 */
case class UpkeepConfig(scanIntervalMs: Int, maxBoxesPerJob: Int, jobs: Map[String, Boolean],
                        heartbeat: HeartbeatConfig = HeartbeatConfig.Default) {
  def jobEnabled(name: String): Boolean = jobs.getOrElse(name, false)
}

object UpkeepConfig {

  final val Path = "stratum.candidate.sources.upkeep"

  /** Mirrors the `stratum.candidate.sources.upkeep` block in `application.conf`; keep them in step. */
  val Default: UpkeepConfig = UpkeepConfig(
    scanIntervalMs = 60000,
    maxBoxesPerJob = 256,
    jobs = Map.empty,
    heartbeat = HeartbeatConfig.Default)

  def apply(config: Configuration): UpkeepConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"$Path.$key")(ConfigLoader.intLoader).getOrElse(fallback)

    val jobs = config.getOptional(s"$Path.jobs")(ConfigLoader.configurationLoader)
      .map { block =>
        block.subKeys.map { name =>
          name -> block.getOptional(s"$name.enabled")(ConfigLoader.booleanLoader).getOrElse(false)
        }.toMap
      }
      .getOrElse(Map.empty[String, Boolean])

    UpkeepConfig(
      scanIntervalMs = int("scanIntervalMs", Default.scanIntervalMs),
      maxBoxesPerJob = int("maxBoxesPerJob", Default.maxBoxesPerJob),
      jobs = jobs,
      heartbeat = HeartbeatConfig(config))
  }
}
