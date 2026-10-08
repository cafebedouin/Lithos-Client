package transactions.upkeep

import akka.actor.{ActorRef, ActorSystem, Kill, Props}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import configs.{CandidateSourceConfig, UpkeepConfig}
import node.NodeApi
import node.model.NodeBox
import org.ergoplatform.appkit.Parameters
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{CanonicalNodeBox, FakeNodeContext, RestartingSupervisor}
import transactions.candidate.BlockTxMessages.{BlockTxsReady, CandidateTxsDropped, PrepareBlockTxs, RequestBlockTxs}
import transactions.candidate.CandidateBundle
import transactions.upkeep.UpkeepSource.{Held, Holding, ScanTick}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.util.Success

object UpkeepSourceSpec {
  val config: com.typesafe.config.Config = ConfigFactory.parseString("""
    akka.test.single-expect-default = 20s
    lithos-contexts.polling-dispatcher.thread-pool-executor.fixed-pool-size = 2
  """).withFallback(ConfigFactory.parseResources("application.conf").resolve())
}

/**
 * The source driven end to end with a job the spec steers, against a node that is a mock.
 *
 * Every property here is about what the actor does between its two halves — what a scan leaves
 * behind, what a build reads, forgets and refuses, and what a request is answered with — rather
 * than about any job's transaction, which is the job's own spec to prove.
 */
class UpkeepSourceSpec extends TestKit(ActorSystem("upkeep-source-spec", UpkeepSourceSpec.config))
  with AnyFlatSpecLike with Matchers with BeforeAndAfterAll with MockitoSugar {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  private val heights = new AtomicInteger(5000)

  private def nextHeight(): Int = heights.incrementAndGet()

  private def id(seed: String): String = (seed * 64).take(64)

  private val limits: CandidateSourceConfig = CandidateSourceConfig.Default

  /**
   * @param jobs       how many of the two fake jobs are handed to the source
   * @param supervised under a restarting parent, for the incarnation test
   */
  private class Fixture(enabled: Boolean = true, jobEnabled: Boolean = true, jobs: Int = 1,
                        maxBoxes: Int = UpkeepConfig.Default.maxBoxesPerJob, supervised: Boolean = false) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)

    /** What the node is asked for, one read per build. */
    val reads = ArrayBuffer.empty[Seq[String]]
    @volatile var live: Seq[NodeBox] = Seq.empty
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val asked = inv.getArgument[Seq[String]](0)
      reads.synchronized(reads += asked)
      Success(live.filter(box => asked.contains(box.boxId)))
    }

    val job = new FakeJob(wallet)
    val other = new FakeJob(wallet, "other")
    val upkeepConfig: UpkeepConfig = UpkeepConfig.Default.copy(maxBoxesPerJob = maxBoxes,
      jobs = Map(job.name -> jobEnabled, other.name -> jobEnabled))
    val enabledJobs: Seq[UpkeepJob] = UpkeepRegistry.enabled(upkeepConfig, Seq(job, other).take(jobs))
    val memory = new UpkeepSource.Memory
    val props: Props = Props(new UpkeepSource(nodeContext, upkeepConfig, limits.copy(enabled = enabled),
      enabledJobs, memory, useTrueProp = false))
    val probe = TestProbe()
    val source: ActorRef =
      if (!supervised) system.actorOf(props)
      else {
        val supervisor = system.actorOf(RestartingSupervisor.props(props))
        probe.send(supervisor, RestartingSupervisor.GetChild)
        probe.expectMsgType[RestartingSupervisor.Child].ref
      }

    /** A box at this wallet's own key, carrying the id its bytes commit to, so the fake job can sign it. */
    def box(seed: String): NodeBox =
      CanonicalNodeBox(id(seed), id(seed), Parameters.OneErg, 0, 100, wallet.contract.ergoTreeHex)

    def request(height: Int = nextHeight()): Seq[CandidateBundle] = {
      probe.send(source, RequestBlockTxs(height, limits.maxTxs))
      val ready = probe.expectMsgType[BlockTxsReady]
      ready.blockHeight shouldBe height
      ready.bundles
    }

    def held(): Held = {
      probe.send(source, Holding)
      probe.expectMsgType[Held]
    }

    /** A scan lands off the mailbox, so the spec waits for what it left behind. */
    def scanUntil(landed: Held => Boolean): Held = {
      source ! ScanTick
      awaitAssert({
        val now = held()
        withClue(s"holding $now: ") { landed(now) shouldBe true }
        now
      }, 20.seconds, 100.millis)
    }

    def nodeTouched: Boolean = !org.mockito.Mockito.mockingDetails(api).getInvocations.isEmpty
  }

  // ─── off, or nothing to do ────────────────────────────────────────────────

  "A disabled source" should "answer empty without reading the node" in {
    val f = new Fixture(enabled = false)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)

    f.source ! ScanTick
    f.request() shouldBe empty

    // The request was handled after the tick, so the tick was ignored rather than still running.
    f.job.discoveries.get shouldBe 0
    f.nodeTouched shouldBe false
  }

  "An enabled source with no enabled job" should "answer empty" in {
    val f = new Fixture(jobEnabled = false)
    f.enabledJobs shouldBe empty
    f.job.discovered = Seq(f.box("a").boxId)

    f.source ! ScanTick
    f.request() shouldBe empty
    f.job.discoveries.get shouldBe 0
    f.nodeTouched shouldBe false
  }

  // ─── scan and revalidation ────────────────────────────────────────────────

  "The scan" should "store what discover returned, and the build forget what the node no longer returns" in {
    val f = new Fixture()
    val a = f.box("a")
    val b = f.box("b")
    f.job.discovered = Seq(a.boxId, b.boxId)
    f.live = Seq(a) // b is already spent

    f.scanUntil(_.tracked.get("fake").contains(Set(a.boxId, b.boxId)))

    val bundles = f.request()
    bundles should have size 1
    val member = bundles.head.members.head
    member.kind shouldBe Upkeep.kind("fake")
    member.inputIds shouldBe Set(a.boxId)
    member.sizeBytes should be > 0
    member.cost should be > 0L
    bundles.head.capital.map(_.value) shouldBe Seq(FakeJob.Tip)
    f.reads.synchronized(f.reads.head.sorted) shouldBe Seq(a.boxId, b.boxId).sorted

    // The read is the revalidation: the id that did not come back is gone from what is held.
    f.held().tracked("fake") shouldBe Set(a.boxId)
    f.request() should have size 1
    f.reads.synchronized(f.reads.last) shouldBe Seq(a.boxId)
  }

  it should "keep at most maxBoxesPerJob ids for one job" in {
    val f = new Fixture(maxBoxes = 1)
    f.job.discovered = Seq(f.box("a").boxId, f.box("b").boxId)

    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty)).tracked("fake") should have size 1
  }

  it should "skip a job whose discovery throws and still offer the others' work" in {
    val f = new Fixture(jobs = 2)
    val a = f.box("a")
    f.other.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.discoverFails = true

    val held = f.scanUntil(_.tracked.get("other").contains(Set(a.boxId)))
    held.tracked.get("fake") shouldBe None
    f.job.discoveries.get should be >= 1

    val bundles = f.request()
    bundles should have size 1
    bundles.head.members.head.kind shouldBe Upkeep.kind("other")
  }

  // ─── due and refused ──────────────────────────────────────────────────────

  "A build" should "not build a box its job says is not due, and not hold that against it" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.isDue = false

    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty))
    f.request() shouldBe empty
    f.job.dueChecks.get shouldBe 1
    f.job.builds.get shouldBe 0
    f.held().refused shouldBe empty

    // Not yet is not a refusal: the same box is built as soon as it is due.
    f.job.isDue = true
    f.request() should have size 1
  }

  it should "refuse a successor spending a box the job never discovered, and remember it" in {
    val f = new Fixture()
    val a = f.box("a")
    val stray = f.box("c")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a, stray)
    f.job.behaviour = FakeJob.SpendAlso(stray)

    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty))
    f.request() shouldBe empty
    f.job.builds.get shouldBe 1
    f.held().refused shouldBe Set(a.boxId)

    f.request() shouldBe empty
    f.job.builds.get shouldBe 1
  }

  it should "not retry a refused build next block" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Refuse

    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty))
    f.request() shouldBe empty
    f.job.builds.get shouldBe 1

    f.request() shouldBe empty
    withClue("a refused box must not be built again: ") { f.job.builds.get shouldBe 1 }
    f.held().refused shouldBe Set(a.boxId)
  }

  it should "treat a build that throws as refused" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Throw

    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty))
    f.request() shouldBe empty
    f.request() shouldBe empty
    f.job.builds.get shouldBe 1
  }

  it should "forget a refusal once no scan finds the box any more" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Refuse

    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty))
    f.request() shouldBe empty
    f.held().refused shouldBe Set(a.boxId)

    // The box changed: its successor has a new id, and the old one is not discovered again.
    f.job.discovered = Seq.empty
    f.scanUntil(_.tracked.get("fake").contains(Set.empty[String]))
    f.held().refused shouldBe empty
  }

  // ─── the candidate protocol ───────────────────────────────────────────────

  "CandidateTxsDropped" should "forget a prepared height so the next request rebuilds it" in {
    val f = new Fixture()
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty))

    val height = nextHeight()
    f.source ! PrepareBlockTxs(height, limits.maxTxs)
    f.request(height) should have size 1
    f.job.builds.get shouldBe 1

    // Prepared once, served from what was prepared.
    f.request(height) should have size 1
    f.job.builds.get shouldBe 1

    f.source ! CandidateTxsDropped(height)
    f.request(height) should have size 1
    f.job.builds.get shouldBe 2
  }

  "A restarted source" should "not re-offer what was refused in the same run" in {
    val f = new Fixture(supervised = true)
    val a = f.box("a")
    f.job.discovered = Seq(a.boxId)
    f.live = Seq(a)
    f.job.behaviour = FakeJob.Refuse

    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty))
    f.request() shouldBe empty
    f.job.builds.get shouldBe 1
    f.held().refused shouldBe Set(a.boxId)

    f.source ! Kill
    // A restart empties the actor's own fields; the refusal lives outside them.
    awaitAssert(f.held().tracked shouldBe empty, 20.seconds, 100.millis)
    f.held().refused shouldBe Set(a.boxId)

    f.scanUntil(_.tracked.get("fake").exists(_.nonEmpty))
    f.request() shouldBe empty
    withClue("the new incarnation built the refused box again: ") { f.job.builds.get shouldBe 1 }
  }
}
