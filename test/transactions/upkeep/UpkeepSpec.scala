package transactions.upkeep

import configs.{CandidateConfig, CandidateSourceConfig, HeartbeatConfig, UpkeepConfig}
import org.ergoplatform.appkit.BlockchainParameters
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBudget

/**
 * The pure half: what the node charges a shape, how many successors a share affords, what the
 * refusal memory holds and for how long, and what config turns on. Driven with values alone, so
 * nothing here needs a node or an actor.
 */
class UpkeepSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  /** A box or transaction id from any seed: the seed's bytes as hex, repeated to 64 characters. */
  private def id(seed: String): String =
    (seed.getBytes("UTF-8").map(b => f"$b%02x").mkString * 64).take(64)

  private def prepared(n: Int, bytes: Int = 100, cost: Long = 1000L,
                       inputs: Set[String] = Set.empty): Upkeep.Prepared = {
    val spends = if (inputs.isEmpty) Set(id(s"box$n")) else inputs
    Upkeep.Prepared("fake", id(s"box$n"), CandidateTx(id(s"tx$n"), "{}", Upkeep.kind("fake"), spends, bytes, cost), Seq.empty)
  }

  private val unbounded = CandidateBudget.Unbounded

  // ─── sizing ───────────────────────────────────────────────────────────────

  /** Mainnet's launch parameters, which is what the mocked node reports too. */
  private val params: BlockchainParameters = {
    val p = mock[BlockchainParameters]
    when(p.getInputCost).thenReturn(2000)
    when(p.getDataInputCost).thenReturn(100)
    when(p.getOutputCost).thenReturn(100)
    when(p.getTokenAccessCost).thenReturn(100)
    p
  }

  "The node's accounting" should "charge the transaction, each input, data input and output, and every token twice" in {
    Upkeep.accountedCost(inputs = 1, dataInputs = 0, outputs = 2, assets = 0, params) shouldBe
      10000L + 2000L + 200L
    Upkeep.accountedCost(inputs = 2, dataInputs = 1, outputs = 3, assets = 4, params) shouldBe
      10000L + 4000L + 100L + 300L + 800L
  }

  "A share" should "afford nothing once any dimension is spent" in {
    Upkeep.Share(slots = 0, bytes = 100L, cost = 100L).full shouldBe true
    Upkeep.Share(slots = 1, bytes = 0L, cost = 100L).full shouldBe true
    Upkeep.Share(slots = 1, bytes = 100L, cost = 0L).full shouldBe true
    Upkeep.Share(slots = 1, bytes = 1L, cost = 1L).full shouldBe false
  }

  it should "say whether a floor fits what is left, before anything is built" in {
    val share = Upkeep.Share(slots = 1, bytes = 500L, cost = 20000L)
    share.affords(500L, 20000L) shouldBe true
    share.affords(501L, 20000L) shouldBe false
    share.affords(500L, 20001L) shouldBe false
    share.copy(slots = 0).affords(1L, 1L) shouldBe false
  }

  it should "take a successor off every dimension it admits" in {
    val share = Upkeep.Share.of(2, CandidateBudget(1000L, 5000L))
    val admitted = share.admit(prepared(1, bytes = 300, cost = 2000L)).getOrElse(fail("not admitted"))
    admitted.slots shouldBe 1
    admitted.bytes shouldBe 700L
    admitted.cost shouldBe 3000L
    admitted.chosen.map(_.boxId) shouldBe Seq(id("box1"))
    admitted.usedBytes(CandidateBudget(1000L, 5000L)) shouldBe 300L
    admitted.usedCost(CandidateBudget(1000L, 5000L)) shouldBe 2000L
  }

  // ─── fitting ──────────────────────────────────────────────────────────────

  "Fitting" should "take everything the share affords, in order" in {
    val all = (1 to 4).map(prepared(_))
    Upkeep.fitting(all, maxTxs = 10, unbounded) shouldBe all
  }

  it should "stop at maxTxs" in {
    val all = (1 to 10).map(prepared(_))
    Upkeep.fitting(all, maxTxs = 3, unbounded) shouldBe all.take(3)
  }

  it should "stop at maxBytes" in {
    val all = (1 to 10).map(prepared(_, bytes = 100))
    Upkeep.fitting(all, maxTxs = 10, CandidateBudget(250L, Long.MaxValue)) shouldBe all.take(2)
  }

  it should "stop at maxCost" in {
    val all = (1 to 10).map(prepared(_, cost = 1000L))
    Upkeep.fitting(all, maxTxs = 10, CandidateBudget(Long.MaxValue, 2500L)) shouldBe all.take(2)
  }

  /** Successors are independent transactions, so a large one says nothing about a small one behind it. */
  it should "pass over one that does not fit and take a smaller one behind it" in {
    val big = prepared(1, bytes = 1000)
    val small = prepared(2, bytes = 100)
    Upkeep.fitting(Seq(big, small), maxTxs = 10, CandidateBudget(500L, Long.MaxValue)) shouldBe Seq(small)
  }

  it should "leave out a second successor spending a box the first already spends" in {
    val first = prepared(1, inputs = Set(id("shared"), id("one")))
    val second = prepared(2, inputs = Set(id("shared"), id("two")))
    val third = prepared(3)
    Upkeep.fitting(Seq(first, second, third), maxTxs = 10, unbounded) shouldBe Seq(first, third)
  }

  it should "take nothing when the share affords no transaction" in {
    Upkeep.fitting((1 to 3).map(prepared(_)), maxTxs = 0, unbounded) shouldBe empty
    Upkeep.fitting((1 to 3).map(prepared(_)), maxTxs = 3, CandidateBudget(10L, 10L)) shouldBe empty
  }

  "A successor's kind" should "name its job, so a refused block says which one built it" in {
    Upkeep.kind("heartbeat") shouldBe "upkeep:heartbeat"
  }

  // ─── the refusal memory ───────────────────────────────────────────────────

  "The refusal memory" should "hold a box for retryAfterScans passes that still find it, then offer it again" in {
    val memory = new UpkeepSource.Memory(retryAfterScans = 3)
    val a = id("a")
    memory.refuse(Set(a))
    memory.refusedIds shouldBe Set(a)
    memory.passesLeft(a) shouldBe Some(3)

    memory.passed(Set(a)) shouldBe empty
    memory.passesLeft(a) shouldBe Some(2)
    memory.passed(Set(a)) shouldBe empty
    memory.passesLeft(a) shouldBe Some(1)
    memory.passed(Set(a)) shouldBe Set(a)
    memory.refusedIds shouldBe empty
    memory.passesLeft(a) shouldBe None
  }

  it should "forget a box at once when a pass no longer finds it, or a build finds it spent" in {
    val memory = new UpkeepSource.Memory(retryAfterScans = 3)
    val (a, b) = (id("a"), id("b"))
    memory.refuse(Set(a, b))
    memory.passed(Set(b)) shouldBe empty
    memory.refusedIds shouldBe Set(b)
    memory.forget(Set(b))
    memory.refusedIds shouldBe empty
  }

  it should "start a box refused again from the full count" in {
    val memory = new UpkeepSource.Memory(retryAfterScans = 2)
    val a = id("a")
    memory.refuse(Set(a))
    memory.passed(Set(a)) shouldBe empty
    memory.refuse(Set(a))
    memory.passesLeft(a) shouldBe Some(2)
  }

  // ─── config ───────────────────────────────────────────────────────────────

  /** As the Lithos maintainers asked: on only when the operator says so, and then job by job. */
  "The default" should "leave the source off and no job on" in {
    CandidateConfig.Default.sources(CandidateSourceConfig.Upkeep).enabled shouldBe false
    UpkeepConfig.Default.jobs shouldBe empty
    UpkeepRegistry.enabled(UpkeepConfig.Default) shouldBe empty
  }

  "The registry" should "know the heartbeat job, under the name validation checks config against" in {
    UpkeepRegistry.all(UpkeepConfig.Default).map(_.name) shouldBe UpkeepRegistry.names
    UpkeepRegistry.names shouldBe Seq("heartbeat")
    UpkeepRegistry.byName(UpkeepConfig.Default, "heartbeat").map(_.name) shouldBe Some("heartbeat")
    UpkeepRegistry.byName(UpkeepConfig.Default, "dexy") shouldBe None
  }

  it should "turn the heartbeat on from config alone" in {
    val on = UpkeepConfig.Default.copy(jobs = Map("heartbeat" -> true))
    UpkeepRegistry.enabled(on).map(_.name) shouldBe Seq("heartbeat")
  }

  "Job flags" should "be read generically from jobs.<name>.enabled" in {
    val config = UpkeepConfig(Configuration.from(Map(
      "stratum.candidate.sources.upkeep.scanIntervalMs" -> 5000,
      "stratum.candidate.sources.upkeep.retryAfterScans" -> 3,
      "stratum.candidate.sources.upkeep.jobs.heartbeat.enabled" -> true,
      "stratum.candidate.sources.upkeep.jobs.dexy.enabled" -> false)))

    config.scanIntervalMs shouldBe 5000
    config.maxBoxesPerJob shouldBe UpkeepConfig.Default.maxBoxesPerJob
    config.retryAfterScans shouldBe 3
    config.jobs shouldBe Map("heartbeat" -> true, "dexy" -> false)
    config.jobEnabled("heartbeat") shouldBe true
    config.jobEnabled("dexy") shouldBe false
    config.jobEnabled("never-mentioned") shouldBe false
  }

  it should "pick out of the registry only the jobs config turns on" in {
    val (_, _, wallet) = support.FakeNodeContext(mock[node.NodeApi], numAddresses = 1)
    val on = new FakeJob(wallet, "on")
    val off = new FakeJob(wallet, "off")
    val config = UpkeepConfig.Default.copy(jobs = Map("on" -> true, "off" -> false))

    UpkeepRegistry.enabled(config, Seq(off, on)).map(_.name) shouldBe Seq("on")
    UpkeepRegistry.enabled(config, Seq.empty) shouldBe empty
  }

  "A missing jobs block" should "read as no job on, with every other key at its default" in {
    val config = UpkeepConfig(Configuration.from(Map("stratum.candidate.sources.upkeep.enabled" -> true)))
    config.jobs shouldBe empty
    config.retryAfterScans shouldBe UpkeepConfig.Default.retryAfterScans
    config.heartbeat shouldBe HeartbeatConfig.Default
  }

  "The heartbeat's box list" should "be read from jobs.heartbeat.boxIds, and leave the job's flag alone" in {
    val ids = Seq("ab" * 32, "cd" * 32)
    val config = UpkeepConfig(Configuration.from(Map(
      "stratum.candidate.sources.upkeep.jobs.heartbeat.boxIds" -> ids)))
    config.heartbeat.boxIds shouldBe ids
    config.jobEnabled("heartbeat") shouldBe false
  }
}
