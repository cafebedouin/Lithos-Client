package transactions.upkeep

import configs.{CandidateConfig, CandidateSourceConfig, UpkeepConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.Configuration
import transactions.candidate.BlockTxMessages.CandidateTx
import transactions.candidate.CandidateBudget

/**
 * The pure half: how many successors a share affords, and what config turns on. Driven with
 * values alone, so nothing here needs a node or an actor.
 */
class UpkeepSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private def prepared(n: Int, bytes: Int = 100, cost: Long = 1000L,
                       inputs: Set[String] = Set.empty): Upkeep.Prepared = {
    val spends = if (inputs.isEmpty) Set(s"box$n") else inputs
    Upkeep.Prepared("fake", s"box$n", CandidateTx(s"tx$n", "{}", Upkeep.kind("fake"), spends, bytes, cost), Seq.empty)
  }

  private val unbounded = CandidateBudget.Unbounded

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
    val first = prepared(1, inputs = Set("shared", "one"))
    val second = prepared(2, inputs = Set("shared", "two"))
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

  // ─── config ───────────────────────────────────────────────────────────────

  /** As the Lithos maintainers asked: on only when the operator says so, and then job by job. */
  "The default" should "leave the source off and no job on" in {
    CandidateConfig.Default.sources(CandidateSourceConfig.Upkeep).enabled shouldBe false
    UpkeepConfig.Default.jobs shouldBe empty
    UpkeepRegistry.all shouldBe empty
  }

  "Job flags" should "be read generically from jobs.<name>.enabled" in {
    val config = UpkeepConfig(Configuration.from(Map(
      "stratum.candidate.sources.upkeep.scanIntervalMs" -> 5000,
      "stratum.candidate.sources.upkeep.jobs.heartbeat.enabled" -> true,
      "stratum.candidate.sources.upkeep.jobs.dexy.enabled" -> false)))

    config.scanIntervalMs shouldBe 5000
    config.maxBoxesPerJob shouldBe UpkeepConfig.Default.maxBoxesPerJob
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

  "A missing jobs block" should "read as no job on" in {
    UpkeepConfig(Configuration.from(Map("stratum.candidate.sources.upkeep.enabled" -> true))).jobs shouldBe empty
  }
}
