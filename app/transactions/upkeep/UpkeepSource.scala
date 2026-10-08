package transactions.upkeep

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.{CandidateSourceConfig, NodeContext, UpkeepConfig}
import node.MutationConversions._
import node.NodeApi
import node.model.NodeBox
import org.ergoplatform.appkit.BlockchainContext
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.{CandidateBundle, CandidateCapital}
import work.lithos.mutations.Contract

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Advances the boxes its jobs maintain inside this miner's own block, with no key and no fee.
 *
 * The same two halves as the storage-rent source, and for the same reason. A timer asks each job
 * what it maintains and remembers only box ids; the candidate path reads those boxes back, keeps
 * the ones the job says are due, has the job build each successor, fits them to this source's
 * share and answers through [[transactions.candidate.CandidatePreparation]]. Ids are all that is
 * kept between the two because the read that fetches a box back is the only check that matters: a
 * box that does not come back is already spent, and a box that does is judged fresh.
 *
 * Nothing on the mining path reads the actor's fields. The build closes over values it is handed
 * and reports back by message, so a scan landing mid-build cannot change what the build sees.
 *
 * Refusals outlive the actor. A box a job could not advance is remembered in a [[UpkeepSource.Memory]]
 * handed in from outside, so a restart — which empties every field here — does not offer the
 * node the same transaction again. The box is forgotten only when a scan stops finding it, which
 * is what happens when it changes: its successor has a new id.
 *
 * Never extractive. This source spends only the boxes its jobs discovered, in the order their
 * scripts allow, and nothing here reads, reorders or front-runs anyone else's transaction.
 */
class UpkeepSource(nodeContext: NodeContext,
                   upkeepConfig: UpkeepConfig,
                   limits: CandidateSourceConfig,
                   jobs: Seq[UpkeepJob],
                   memory: UpkeepSource.Memory,
                   useTrueProp: Boolean) extends Actor {

  import UpkeepSource._

  require(jobs.map(_.name).distinct.size == jobs.size,
    s"upkeep job names must be distinct: ${jobs.map(_.name).mkString(", ")}")

  private val logger: Logger = LoggerFactory.getLogger("UpkeepSource")

  private implicit val ec: ExecutionContext = context.dispatcher
  private val candidateWorker: ExecutionContext =
    context.system.dispatchers.lookup(configs.Contexts.key(configs.Contexts.Polling))
  private val preparation = new transactions.candidate.CandidatePreparation(candidateWorker, self)

  protected def nodeApi: NodeApi = nodeContext.getNodeApi

  /**
   * Whether this source has anything to do at all. Decided once, because neither input changes
   * while the actor lives, and a source with nothing to do must make no node read: not on the
   * timer, which is never started, and not on a request, which is answered empty at once.
   */
  private val active: Boolean = limits.enabled && jobs.nonEmpty

  /** What each job's last successful discovery returned, less what builds have since found spent. */
  private var tracked: Map[String, Set[String]] = Map.empty

  private var scanning: Boolean = false
  private var ticker: Option[Cancellable] = None

  override def preStart(): Unit =
    if (!active) {
      logger.info(s"UpkeepSource idle: enabled=${limits.enabled}, jobs=${jobs.map(_.name).mkString(", ")}")
    } else {
      logger.info(s"UpkeepSource started: jobs=${jobs.map(_.name).mkString(", ")}, " +
        s"scanIntervalMs=${upkeepConfig.scanIntervalMs}, maxBoxesPerJob=${upkeepConfig.maxBoxesPerJob}, " +
        s"refusedHeld=${memory.refusedIds.size}")
      ticker = Some(context.system.scheduler.scheduleWithFixedDelay(
        upkeepConfig.scanIntervalMs.milliseconds, upkeepConfig.scanIntervalMs.milliseconds,
        self, ScanTick)(context.dispatcher))
    }

  override def postStop(): Unit = ticker.foreach(_.cancel())

  override def receive: Receive = upkeepReceive.orElse(preparation.receive)

  private def upkeepReceive: Receive = {

    case ScanTick if active && !scanning =>
      scanning = true
      Future(scan())(candidateWorker).onComplete(result => self ! Scanned(result))

    case ScanTick => ()

    // A job whose discovery failed is absent from the pass and keeps what it had: stale ids cost
    // one read each and are forgotten by it, while dropping them would lose the boxes until the
    // node recovers.
    case Scanned(Success(pass)) =>
      scanning = false
      tracked ++= pass
      val known = tracked.values.flatten.toSet
      memory.retain(known)
      if (known.nonEmpty)
        logger.info(s"Upkeep scan holds ${known.size} boxes: " +
          tracked.map { case (job, ids) => s"$job=${ids.size}" }.mkString(", ") +
          s"; ${memory.refusedIds.size} refused")

    case Scanned(Failure(ex)) =>
      scanning = false
      logger.warn(s"Upkeep scan failed, retrying next pass: ${ex.getMessage}")

    // Found spent while a build was reading them back. Forgotten rather than retried: nothing
    // brings a spent box back, and its successor has an id the next scan will find on its own.
    case Spent(ids) =>
      tracked = tracked.map { case (job, held) => job -> (held -- ids) }
      memory.retain(tracked.values.flatten.toSet)

    // A job said due and then could not advance the box, or advanced it with a transaction this
    // source will not carry. Offered every block, each would cost a build and a node read for the
    // same answer, so the box is dropped until a scan stops finding it.
    case Refused(ids) =>
      memory.refuse(ids)
      logger.warn(s"Dropped ${ids.size} upkeep boxes whose builds were refused; holding ${memory.refusedIds.size}")

    // What the source holds, for a spec that must wait for a pass to land before asking for a
    // block. Nothing on the mining path sends it.
    case Holding => sender() ! Held(tracked, memory.refusedIds)

    // ── the candidate path ──────────────────────────────────────────────────

    case PrepareBlockTxs(blockHeight, _) => startBuild(blockHeight, None)

    case RequestBlockTxs(blockHeight, _, refresh) =>
      val replyTo = sender()
      preparation.preparedFor(blockHeight).filterNot(_ => refresh) match {
        case Some(bundles) => replyTo ! BlockTxsReady(blockHeight, bundles)
        case None => startBuild(blockHeight, Some(replyTo))
      }

    // Nothing to reconcile: a successor holds no wallet input and was never broadcast, so a
    // dropped height leaves only the work itself, which is rebuilt for the next one.
    case CandidateTxsDropped(blockHeight) => preparation.drop(blockHeight)
  }

  private def startBuild(blockHeight: Int, replyTo: Option[ActorRef]): Unit = {
    // Read here rather than inside the build: everything the build touches has to be a value it
    // was handed, because it runs off the mailbox.
    val refused = memory.refusedIds
    val work = if (!active) Seq.empty[JobWork] else jobs.flatMap { job =>
      val discovered = tracked.getOrElse(job.name, Set.empty[String])
      val offered = discovered -- refused
      if (offered.isEmpty) None else Some(JobWork(job, discovered, offered))
    }
    if (work.isEmpty) preparation.answerEmpty(blockHeight, replyTo)
    else preparation.start(blockHeight, replyTo)(advance(work, blockHeight))
  }

  /**
   * Read every offered box back, build a successor for each one its job says is due, and offer
   * what fits.
   *
   * The read is the revalidation: ids that do not come back are spent and are forgotten. A read
   * that fails altogether builds nothing and forgets nothing, because the boxes are most likely
   * still there and the next block will ask again.
   */
  private def advance(work: Seq[JobWork], blockHeight: Int): Seq[CandidateBundle] =
    Try(nodeContext.getClient.execute { ctx =>
      val ids = work.flatMap(_.offered).distinct
      val live = nodeApi.boxesWithPoolByIds(ids) match {
        case Success(boxes) => boxes
        case Failure(ex) =>
          throw new IllegalStateException(s"could not read ${ids.size} upkeep boxes back: ${ex.getMessage}")
      }
      val byId = live.map(box => box.boxId -> box).toMap
      val gone = ids.toSet -- byId.keySet
      if (gone.nonEmpty) self ! Spent(gone)

      val payTo = CandidateCapital.collectionContract(nodeContext.getNodeWallet, useTrueProp)
      var refused = Set.empty[String]
      val prepared = work.flatMap { item =>
        item.offered.toSeq.sorted.flatMap(byId.get).flatMap { box =>
          attempt(ctx, item, box, blockHeight, payTo) match {
            case Attempt.Ready(successor) => Some(successor)
            case Attempt.NotDue => None
            case Attempt.Refused(reason) =>
              logger.warn(s"Upkeep job ${item.job.name} cannot advance ${box.boxId} at $blockHeight: " +
                s"$reason; it is no longer offered")
              refused += box.boxId
              None
          }
        }
      }
      if (refused.nonEmpty) self ! Refused(refused)

      val chosen = Upkeep.fitting(prepared, limits.maxTxs, limits.budget)
      if (chosen.nonEmpty)
        logger.info(s"Upkeep offers ${chosen.size} of ${prepared.size} successors at $blockHeight: " +
          chosen.map(c => s"${c.job}:${c.boxId.take(8)}").mkString(", "))
      chosen.map(_.bundle)
    }) match {
      case Success(bundles) => bundles
      case Failure(ex) =>
        logger.warn(s"Upkeep built nothing for $blockHeight: ${ex.getMessage}")
        Seq.empty[CandidateBundle]
    }

  /**
   * One box through its job: due or not, and if due, a successor or the reason there is none.
   *
   * Every call into the job is caught, because a job is reviewed code but not trusted code, and a
   * throw on one box must not cost the block every other job's work. A box whose serialized form
   * does not hash to the id the node gave it is refused before the job sees it: a transaction
   * built from it would spend a box that does not exist.
   */
  private def attempt(ctx: BlockchainContext, item: JobWork, box: NodeBox, blockHeight: Int,
                      payTo: Contract): Attempt = {
    val job = item.job
    val input = box.toInputUTXO(ctx)
    if (input.id.toString != box.boxId)
      Attempt.Refused(s"the box serializes to ${input.id.toString}, not the id the node reports")
    else Try(job.due(input, blockHeight)) match {
      case Failure(ex) => Attempt.Refused(s"due() failed: ${ex.getMessage}")
      case Success(false) => Attempt.NotDue
      case Success(true) =>
        Try {
          job.build(ctx, input, blockHeight, payTo) match {
            case None => Left("the job could not build a successor")
            case Some(built) =>
              val foreign = Upkeep.undiscoveredInputs(built.tx, item.discovered)
              if (foreign.nonEmpty)
                Left(s"its successor spends ${foreign.size} box(es) the job never discovered: ${foreign.mkString(", ")}")
              else Right(Upkeep.Prepared(job.name, box.boxId, Upkeep.member(built.tx, job.name), built.capital))
          }
        } match {
          case Failure(ex) => Attempt.Refused(s"build failed: ${ex.getMessage}")
          case Success(Left(reason)) => Attempt.Refused(reason)
          case Success(Right(successor)) => Attempt.Ready(successor)
        }
    }
  }

  /**
   * One pass over every job. A job that throws is logged and left out of the pass, so one
   * protocol's node trouble cannot stop the others; the actor keeps what that job last found.
   */
  private def scan(): Map[String, Set[String]] =
    nodeContext.getClient.execute { ctx =>
      val height = ctx.getHeight
      jobs.flatMap { job =>
        Try(job.discover(ctx, nodeApi, height)) match {
          case Success(found) =>
            val distinct = found.distinct
            if (distinct.size > upkeepConfig.maxBoxesPerJob)
              logger.warn(s"Upkeep job ${job.name} found ${distinct.size} boxes; keeping the first " +
                s"${upkeepConfig.maxBoxesPerJob} (stratum.candidate.sources.upkeep.maxBoxesPerJob)")
            Some(job.name -> distinct.take(upkeepConfig.maxBoxesPerJob).toSet)
          case Failure(ex) =>
            logger.warn(s"Upkeep job ${job.name} failed to discover its boxes, keeping the last pass: ${ex.getMessage}")
            None
        }
      }.toMap
    }
}

object UpkeepSource {

  /**
   * Boxes whose builds were refused, held outside the actor so a restart cannot forget them.
   *
   * Akka rebuilds a restarted actor from its `Props`, which empties every field; a refusal that
   * lived in one would be offered to the node again by the new incarnation, at a build and a node
   * read per block. Constructed once where the source is wired and handed to every incarnation.
   * Only the actor's own thread touches it, so the atomic is for visibility across restarts, not
   * for contention.
   */
  final class Memory {
    private val refused = new AtomicReference[Set[String]](Set.empty[String])

    def refusedIds: Set[String] = refused.get()

    def refuse(ids: Set[String]): Unit = refused.updateAndGet(held => held ++ ids)

    /** Forget every refusal outside `ids`: a box no scan finds any more has changed or gone. */
    def retain(ids: Set[String]): Unit = refused.updateAndGet(held => held.intersect(ids))
  }

  /** Scheduler tick: ask every job what it maintains. */
  private[upkeep] case object ScanTick

  /** What one pass found per job; a job whose discovery failed is absent. */
  private[upkeep] final case class Scanned(result: Try[Map[String, Set[String]]])

  /** Boxes a build found already spent, so they stop being offered. */
  private[upkeep] final case class Spent(ids: Set[String])

  /** Boxes whose builds were refused, so they stop being offered until a scan loses them. */
  private[upkeep] final case class Refused(ids: Set[String])

  /** Ask what the source holds; answered with [[Held]]. For specs. */
  private[upkeep] case object Holding

  private[upkeep] final case class Held(tracked: Map[String, Set[String]], refused: Set[String])

  /**
   * One job's share of a build: what it discovered, which the rule-1 check is made against, and
   * what is still offered, which is that less the refusals.
   */
  private final case class JobWork(job: UpkeepJob, discovered: Set[String], offered: Set[String])

  private sealed trait Attempt

  private object Attempt {
    final case class Ready(successor: Upkeep.Prepared) extends Attempt
    case object NotDue extends Attempt
    final case class Refused(reason: String) extends Attempt
  }
}
