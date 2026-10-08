package configs

import play.api.{ConfigLoader, Configuration}

/**
 * What the heartbeat job is told about the boxes it maintains, beyond whether it runs.
 *
 * On a node with the extra index the job finds every box at the `DueJob` script by itself and needs
 * nothing here. On a plain node there is no way to ask for boxes by script, so the operator names
 * them: this list is the fallback, read back on every scan.
 *
 * @param boxIds boxes to maintain on a node without `extraIndex`. These are the boxes as they
 *               stand: a beat gives the box a new id, which a plain node cannot be asked to follow,
 *               so the list has to be refreshed once a listed box has been advanced.
 */
case class HeartbeatConfig(boxIds: Seq[String])

object HeartbeatConfig {

  final val Path = s"${UpkeepConfig.Path}.jobs.heartbeat"

  /** Mirrors the `jobs.heartbeat` block in `application.conf`; keep them in step. */
  val Default: HeartbeatConfig = HeartbeatConfig(boxIds = Seq.empty)

  def apply(config: Configuration): HeartbeatConfig =
    HeartbeatConfig(
      boxIds = config.getOptional(s"$Path.boxIds")(ConfigLoader.seqStringLoader).getOrElse(Default.boxIds))
}
