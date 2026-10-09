package configs

import play.api.{ConfigLoader, Configuration}

/**
 * How this client looks for the boxes its upkeep jobs maintain, and which jobs it runs.
 *
 * Jobs are read generically from `jobs.<name>`, so registering a new job needs no config code: the
 * registry decides what a name means, and config only says whether it runs, which boxes it is told
 * about, and whatever keys of its own the job's factory reads from its block. A name that is not in
 * the registry is caught by validation, not here.
 *
 * @param scanIntervalMs gap between discovery passes. Discovery is background work and must never
 *                       be on the path a block is built on.
 * @param maxBoxesPerJob  box ids one job may hold between passes. A job that finds more keeps the
 *                        first this many, so one busy protocol cannot grow the actor's memory or
 *                        the build's node read without bound.
 * @param retryAfterScans discovery passes a box whose build was refused sits out before it is
 *                        offered again. A refusal may be the box's own doing or the moment's — a
 *                        node that could not be read — and the source cannot tell which, so it
 *                        retries after this many passes rather than never; a box refused again
 *                        sits out as many again.
 * @param jobs            each configured job by name. Absent is off.
 */
case class UpkeepConfig(scanIntervalMs: Int, maxBoxesPerJob: Int, retryAfterScans: Int,
                        jobs: Map[String, UpkeepConfig.Job]) {
  def jobEnabled(name: String): Boolean = jobs.get(name).exists(_.enabled)
}

object UpkeepConfig {

  final val Path = "stratum.candidate.sources.upkeep"

  /**
   * One job's block under `jobs.<name>`: the keys every job may carry, read here once for all of
   * them, and the block itself for the keys only that job's factory knows.
   *
   * @param enabled whether the job runs. Off unless config says otherwise.
   * @param boxIds  boxes to maintain on a node without `extraIndex`, which cannot be asked for boxes
   *                by script. These are the boxes as they stand: advancing a box gives it a new id,
   *                which a plain node cannot be asked to follow, so the list has to be refreshed
   *                once a listed box has been advanced.
   * @param block   the whole block, for the job's own keys
   */
  final case class Job(enabled: Boolean = false, boxIds: Seq[String] = Seq.empty,
                       block: Configuration = Configuration.empty)

  object Job {
    def apply(block: Configuration): Job = Job(
      enabled = block.getOptional("enabled")(ConfigLoader.booleanLoader).getOrElse(false),
      boxIds = block.getOptional("boxIds")(ConfigLoader.seqStringLoader).getOrElse(Seq.empty),
      block = block)
  }

  /** Mirrors the `stratum.candidate.sources.upkeep` block in `application.conf`; keep them in step. */
  val Default: UpkeepConfig = UpkeepConfig(
    scanIntervalMs = 60000,
    maxBoxesPerJob = 256,
    retryAfterScans = 10,
    jobs = Map.empty)

  def apply(config: Configuration): UpkeepConfig = {
    def int(key: String, fallback: Int): Int =
      config.getOptional(s"$Path.$key")(ConfigLoader.intLoader).getOrElse(fallback)

    val jobs = config.getOptional(s"$Path.jobs")(ConfigLoader.configurationLoader)
      .map { block =>
        block.subKeys.map { name =>
          name -> Job(block.getOptional(name)(ConfigLoader.configurationLoader).getOrElse(Configuration.empty))
        }.toMap
      }
      .getOrElse(Map.empty[String, Job])

    UpkeepConfig(
      scanIntervalMs = int("scanIntervalMs", Default.scanIntervalMs),
      maxBoxesPerJob = int("maxBoxesPerJob", Default.maxBoxesPerJob),
      retryAfterScans = int("retryAfterScans", Default.retryAfterScans),
      jobs = jobs)
  }
}
