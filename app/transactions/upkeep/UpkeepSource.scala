package transactions.upkeep

import akka.actor.{Actor, ActorRef, Cancellable}
import configs.{CandidateSourceConfig, NodeContext, UpkeepConfig}
import node.MutationConversions._
import node.NodeApi
import node.model.NodeBox
import org.slf4j.{Logger, LoggerFactory}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.{CandidateBundle, CandidateCapital}
import work.lithos.mutations.InputUTXO

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Advances the boxes its jobs maintain inside this miner's own block, with no key and no fee.
 *
 * The same two halves as the storage-rent source, and for the same reason. A timer asks each job
 * what it maintains and remembers only box ids; the candidate path reads those boxes back, keeps
 * the ones the job says are due, has the job build each successor until this source's share is
 * spent, and answers through [[transactions.candidate.CandidatePreparation]]. Ids are all that is
 * kept between the two because the read that fetches a box back is the only check that matters: a
 * box that does not come back is already spent, and a box that does is judged fresh.
 *
 * Nothing on the mining path reads the actor's fields. The build closes over values it is handed
 * and reports back by message, so a scan landing mid-build cannot change what the build sees.
 *
 * Refusals outlive the actor. A box a job could not advance is remembered in a [[UpkeepSource.Memory]]
 * handed in from outside, so a restart — which empties every field here — does not offer the
 * node the same transaction again. The box is forgotten when a scan stops finding it, which is
 * what happens when it changes, or offered once more after the configured number of passes, in
 * case what refused it has passed too.
 *
 * One box's trouble stays with that box. Every call into a job is caught, and a box whose
 * successor cannot be built, or cannot even be read, is refused on its own; the others in the same
 * job and every other job's are offered as usual, and the source answers whatever happened.
 *
 * In observe mode (`mode = "observe"`) everything above runs as it would, and each successor the
 * share admits is put through the node's transaction check before the block is answered empty.
 * The check is the node's own verdict on the transaction as it would have been offered, which is
 * the one thing a miner with no block yet cannot otherwise see; it is one node call per successor,
 * made on the build thread like the rest of the build, and a refusal there is logged and nothing
 * more, because what is being watched is the job, and a box set aside would stop being watched.
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
      logger.info(s"UpkeepSource started: mode=${upkeepConfig.mode}, jobs=${jobs.map(_.name).mkString(", ")}, " +
        s"scanIntervalMs=${upkeepConfig.scanIntervalMs}, maxBoxesPerJob=${upkeepConfig.maxBoxesPerJob}, " +
        s"retryAfterScans=${memory.retryAfterScans}, share: txs=${limits.maxTxs}, " +
        s"bytes=${limits.maxBytes}, cost=${limits.maxCost}; refusedHeld=${memory.refusedIds.size}")
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
      val retried = memory.passed(known)
      if (retried.nonEmpty)
        logger.info(s"Upkeep offers ${retried.size} refused boxes again after ${memory.retryAfterScans} " +
          s"passes: ${retried.toSeq.sorted.map(_.take(8)).mkString(", ")}")
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
      memory.forget(ids)

    // A job said due and then could not advance the box, or advanced it with a transaction this
    // source will not carry. Offered every block, each would cost a build and a node read for the
    // same answer, so the box is dropped until a scan stops finding it or enough passes go by.
    case Refused(ids) =>
      memory.refuse(ids)
      logger.warn(s"Dropped ${ids.size} upkeep boxes whose builds were refused; holding ${memory.refusedIds.size}")

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
   * Read every offered box back, build a successor for each one its job says is due until the
   * share is spent, and offer what fits.
   *
   * The read is the revalidation: ids that do not come back are spent and are forgotten. A read
   * that fails altogether builds nothing and forgets nothing, because the boxes are most likely
   * still there and the next block will ask again.
   *
   * Building stops at the share rather than at the boxes. Every due box costs a signing, and a
   * busy job may hold many more than the share has slots for, so a box is sized before it is
   * signed — from its own bytes and the node's parameters — and left for a later block when its
   * floor does not fit, and nothing at all is signed once the share is full. The boxes left over
   * are not refused: they are tried again next block, in a different order once the advanced
   * ones have new ids.
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

      // Read once for the block: every job builds against the same parameters, height and payTo.
      val bc = BuildContext(ctx, blockHeight,
        CandidateCapital.collectionContract(nodeContext.getNodeWallet, useTrueProp))
      var share = Upkeep.Share.of(limits.maxTxs, limits.budget)
      var refused = Set.empty[String]
      var due = 0
      var deferred = 0
      val queue = work.iterator.flatMap(item => item.offered.toSeq.sorted.flatMap(byId.get).map(item -> _))
      while (!share.full && queue.hasNext) {
        val (item, box) = queue.next()
        attempt(bc, item, box, share) match {
          case Attempt.Ready(successor, admitted) =>
            due += 1
            share = admitted
            logger.debug(s"Upkeep ${successor.label} fits at $blockHeight: " +
              s"${successor.tx.sizeBytes}B, ${successor.tx.cost} cost")
          case Attempt.NotDue => ()
          case Attempt.Deferred(reason) =>
            due += 1
            deferred += 1
            logger.debug(s"Upkeep job ${item.job.name} leaves ${box.boxId} for a later block: $reason")
          case Attempt.Refused(reason) =>
            logger.warn(s"Upkeep job ${item.job.name} cannot advance ${box.boxId} at $blockHeight: " +
              s"$reason; it is no longer offered")
            refused += box.boxId
        }
      }
      if (refused.nonEmpty) self ! Refused(refused)

      val chosen = share.chosen
      if (chosen.nonEmpty || deferred > 0)
        logger.info(s"Upkeep ${if (upkeepConfig.observing) "would offer" else "offers"} " +
          s"${chosen.size} of $due due successors at $blockHeight" +
          (if (deferred > 0) s", $deferred left for a later block" else "") +
          (if (queue.hasNext) ", share full before every box was tried" else "") +
          s": ${chosen.map(_.label).mkString(", ")}; share used " +
          s"txs=${chosen.size}/${limits.maxTxs}, bytes=${share.usedBytes(limits.budget)}/${limits.maxBytes}, " +
          s"cost=${share.usedCost(limits.budget)}/${limits.maxCost}")
      if (upkeepConfig.observing) {
        chosen.foreach(observe(_, blockHeight))
        Seq.empty[CandidateBundle]
      } else chosen.map(_.bundle)
    }) match {
      case Success(bundles) => bundles
      case Failure(ex) =>
        logger.warn(s"Upkeep built nothing for $blockHeight: ${ex.getMessage}")
        Seq.empty[CandidateBundle]
    }

  /**
   * One box through its job: due or not; if due, whether it is worth building against what is
   * left of the share; and if built, a successor admitted to the share or the reason there is none.
   *
   * Every call into the job is caught, because a job is reviewed code but not trusted code, and a
   * throw on one box must not cost the block every other box's successor. Parsing the box is
   * inside the same net: a box the node reports in a form this client cannot read is that box's
   * problem. One whose serialized form does not hash to the id the node gave it is refused before
   * the job sees it, since a transaction built from it would spend a box that does not exist.
   */
  private def attempt(bc: BuildContext, item: JobWork, box: NodeBox, share: Upkeep.Share): Attempt = {
    val job = item.job
    Try(box.toInputUTXO(bc.ctx)) match {
      case Failure(ex) => Attempt.Refused(s"the box cannot be read: ${ex.getMessage}")
      case Success(input) if input.id.toString != box.boxId =>
        Attempt.Refused(s"the box serializes to ${input.id.toString}, not the id the node reports")
      case Success(input) => Try(job.due(input, bc.height)) match {
        case Failure(ex) => Attempt.Refused(s"due() failed: ${ex.getMessage}")
        case Success(false) => Attempt.NotDue
        case Success(true) =>
          val (floorBytes, floorCost) = Upkeep.floor(input, bc.params)
          if (!share.affords(floorBytes, floorCost))
            Attempt.Deferred(s"at least ${floorBytes}B and $floorCost cost, over the " +
              s"${share.bytes}B and ${share.cost} cost left of the share")
          else build(bc, item, input) match {
            case Left(reason) => Attempt.Refused(reason)
            case Right(successor) => share.admit(successor) match {
              case Some(admitted) => Attempt.Ready(successor, admitted)
              case None if successor.tx.inputIds.exists(share.claimed.contains) =>
                Attempt.Deferred("its successor spends a box one already admitted this block spends")
              case None => Attempt.Deferred(s"built at ${successor.tx.sizeBytes}B and ${successor.tx.cost} cost, " +
                s"over the ${share.bytes}B and ${share.cost} cost left of the share")
            }
          }
      }
    }
  }

  /** The job's successor for one due box, sized, or why this source will not carry it. */
  private def build(bc: BuildContext, item: JobWork, input: InputUTXO): Either[String, Upkeep.Prepared] =
    Try {
      item.job.build(input, bc) match {
        case None => Left("the job could not build a successor")
        case Some(built) =>
          val foreign = Upkeep.undiscoveredInputs(built.tx, item.discovered)
          if (foreign.nonEmpty)
            Left(s"its successor spends ${foreign.size} box(es) the job never discovered: ${foreign.mkString(", ")}")
          else Right(Upkeep.Prepared(item.job.name, input.id.toString,
            Upkeep.member(built.tx, item.job.name, input, bc.params), built.capital))
      }
    } match {
      case Failure(ex) => Left(s"build failed: ${ex.getMessage}")
      case Success(outcome) => outcome
    }

  /**
   * Observe mode's report on one successor the block would have carried: the node's verdict on the
   * signed transaction, and what it would have cost the block and paid this miner. A check that
   * cannot be made at all is reported as such, and costs only this line.
   */
  private def observe(successor: Upkeep.Prepared, blockHeight: Int): Unit = {
    val offer = s"Upkeep observe at $blockHeight: ${successor.label} as tx ${successor.tx.id}, " +
      s"${successor.tx.sizeBytes}B, ${successor.tx.cost} cost, paying " +
      s"${successor.capital.map(_.value).sum} nanoERG to this miner"
    Try(nodeApi.checkTransaction(successor.tx.json)).flatten match {
      case Success(_) => logger.info(s"$offer: the node's check accepts it")
      case Failure(ex) => logger.warn(s"$offer: the node's check refuses it: ${ex.getMessage}")
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
          // The ids the operator listed are kept whatever their number; the cap cuts only the
          // rest, which a job returns in its own priority order.
          case Success(found) =>
            val (listed, rest) = found.distinct.partition(job.configured.contains)
            if (rest.size > upkeepConfig.maxBoxesPerJob)
              logger.warn(s"Upkeep job ${job.name} found ${rest.size} boxes beyond its configured ones; " +
                s"keeping the first ${upkeepConfig.maxBoxesPerJob} (stratum.candidate.sources.upkeep.maxBoxesPerJob)")
            Some(job.name -> (listed ++ rest.take(upkeepConfig.maxBoxesPerJob)).toSet)
          case Failure(ex) =>
            logger.warn(s"Upkeep job ${job.name} failed to discover its boxes, keeping the last pass: ${ex.getMessage}")
            None
        }
      }.toMap
    }
}

object UpkeepSource {

  /**
   * Boxes whose builds were refused, held outside the actor so a restart cannot forget them, and
   * how many passes each has left to sit out.
   *
   * Akka rebuilds a restarted actor from its `Props`, which empties every field; a refusal that
   * lived in one would be offered to the node again by the new incarnation, at a build and a node
   * read per block. Constructed once where the source is wired and handed to every incarnation.
   * Only the actor's own thread touches it, so the atomic is for visibility across restarts, not
   * for contention.
   *
   * A refusal is not for good. What refused a box may have been the box — a successor it can no
   * longer pay for — or may have been the moment: a node that could not be read, a job tripped by
   * something that has since passed. The two cannot be told apart from here, so every refusal
   * expires after `retryAfterScans` passes have found the box still unchanged, and a box refused
   * again sits out as many passes again. A box no pass finds any more is forgotten at once, since
   * it has changed or gone.
   *
   * @param retryAfterScans passes a refused box sits out, counted from the first that lands after
   *                        the refusal. Read from config where the memory is wired and held only
   *                        here, so the source's logs and its retry rule cannot disagree.
   */
  final class Memory(val retryAfterScans: Int) {
    require(retryAfterScans > 0, s"retryAfterScans must be positive, not $retryAfterScans")

    private val refused = new AtomicReference[Map[String, Int]](Map.empty[String, Int])

    def refusedIds: Set[String] = refused.get().keySet

    /** Passes `id` has left to sit out, or nothing when it is not refused. */
    def passesLeft(id: String): Option[Int] = refused.get().get(id)

    def refuse(ids: Set[String]): Unit =
      refused.set(refused.get() ++ ids.map(_ -> retryAfterScans))

    /** Boxes a build found spent: nothing to retry. */
    def forget(ids: Set[String]): Unit = refused.set(refused.get() -- ids)

    /**
     * One pass landed, finding `known`. Drops every refusal outside it, counts the pass against
     * the rest, and returns the ids that have sat out their passes and are offered again.
     */
    def passed(known: Set[String]): Set[String] = {
      val held = refused.get()
      val kept = held.filter { case (id, _) => known.contains(id) }
      val (expired, remaining) = kept.partition { case (_, left) => left <= 1 }
      refused.set(remaining.map { case (id, left) => id -> (left - 1) })
      expired.keySet
    }
  }

  /** Scheduler tick: ask every job what it maintains. */
  private[upkeep] case object ScanTick

  /** What one pass found per job; a job whose discovery failed is absent. */
  private[upkeep] final case class Scanned(result: Try[Map[String, Set[String]]])

  /** Boxes a build found already spent, so they stop being offered. */
  private[upkeep] final case class Spent(ids: Set[String])

  /** Boxes whose builds were refused, so they stop being offered until a scan loses them. */
  private[upkeep] final case class Refused(ids: Set[String])

  /**
   * One job's share of a build: what it discovered, which the rule-1 check is made against, and
   * what is still offered, which is that less the refusals.
   */
  private final case class JobWork(job: UpkeepJob, discovered: Set[String], offered: Set[String])

  private sealed trait Attempt

  private object Attempt {
    /** Built, measured and admitted; `share` is what is left after it. */
    final case class Ready(successor: Upkeep.Prepared, share: Upkeep.Share) extends Attempt
    case object NotDue extends Attempt
    /** Due, but not this block: nothing is wrong with the box, the share has no room for it. */
    final case class Deferred(reason: String) extends Attempt
    final case class Refused(reason: String) extends Attempt
  }
}
