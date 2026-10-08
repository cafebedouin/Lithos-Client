package transactions.upkeep

import node.MutationConversions._
import node.NodeApi
import node.model._
import org.ergoplatform.appkit.{BlockchainContext, ErgoValue, Parameters}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import support.{CanonicalNodeBox, FakeNodeContext}
import transactions.engine.execution.RollupExecution
import transactions.upkeep.jobs.HeartbeatJob
import transactions.upkeep.jobs.HeartbeatJob.Beat
import work.lithos.mutations.{Contract, InputUTXO}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

/**
 * The heartbeat job against a mocked node: what discovery returns on each kind of node, what
 * `due` says at the boundary, and what a build produces. The contract's own rules are
 * [[contracts.specs.upkeep.DueJobSpec]]'s to prove; here the successor is checked against them
 * field by field, and signing it offline is what shows the keyless prover does its job.
 */
class HeartbeatJobSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val period: Int = 10
  private val tip: Long = Parameters.OneErg / 100
  private val tokenId: String = "ab" * 32

  /** A box id from any seed: the seed's bytes as hex, repeated, so `CanonicalNodeBox` can decode it. */
  private def id(seed: String): String =
    (seed.getBytes("UTF-8").map(b => f"$b%02x").mkString * 64).take(64)

  /**
   * @param indexed whether the node reports the extra index
   * @param boxIds  the configured fallback list
   */
  private class Fixture(indexed: Boolean = true, boxIds: Seq[String] = Seq.empty) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val client = nodeContext.getClient
    val job = new HeartbeatJob(boxIds)
    val tree: String = client.execute(ctx => HeartbeatJob.contract(ctx.getNetworkType).ergoTreeHex)

    /** What the index holds, and what a read by id answers from. */
    @volatile var atTree: Seq[NodeBox] = Seq.empty
    @volatile var live: Seq[NodeBox] = Seq.empty
    @volatile var indexDown: Boolean = false
    val pages = new AtomicInteger(0)

    when(api.indexerEnabled).thenReturn(indexed)
    when(api.unspentBoxesByErgoTree(any[String], any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        pages.incrementAndGet()
        if (indexDown) Failure(new RuntimeException("the index is down"))
        else {
          val asked = inv.getArgument[String](0)
          val paging = inv.getArgument[Paging](1)
          val all = atTree.filter(_.ergoTree == asked)
          Success(all.slice(paging.offset, paging.offset + paging.limit)
            .map(box => IndexedBox(box, "", box.creationHeight, 1L)))
        }
      }
    when(api.boxesWithPoolByIds(any[Seq[String]])).thenAnswer { inv =>
      val asked = inv.getArgument[Seq[String]](0)
      Success(live.filter(box => asked.contains(box.boxId)))
    }

    def discover(): Seq[String] = client.execute(ctx => job.discover(ctx, api, ctx.getHeight))

    /** A due-job box as the node reports it, with the id its bytes commit to. */
    def dueBox(seed: String, lastBeat: Int = 1000, period: Int = period, tip: Long = tip,
               value: Long = Parameters.OneErg, assets: Seq[NodeAsset] = Seq.empty,
               registers: Seq[ErgoValue[_]] = null): NodeBox = {
      val regs: Seq[ErgoValue[_]] =
        if (registers == null) Seq(ErgoValue.of(lastBeat), ErgoValue.of(period), ErgoValue.of(tip))
        else registers
      CanonicalNodeBox(id(seed), id(seed), value, 0, lastBeat, tree, assets,
        NodeRegisters(regs.zipWithIndex.map { case (v, i) => s"R${i + 4}" -> v.toHex }.toMap))
    }

    /** A box at this wallet's key: what a mistaken entry in the configured list looks like. */
    def strayBox(seed: String): NodeBox =
      CanonicalNodeBox(id(seed), id(seed), Parameters.OneErg, 0, 1000, wallet.contract.ergoTreeHex)
  }

  // ─── discovery ────────────────────────────────────────────────────────────

  "Discovery on an indexed node" should "list every well-formed box at the contract and skip the rest" in {
    val f = new Fixture()
    val a = f.dueBox("a")
    val b = f.dueBox("b", lastBeat = 2000, period = 1, tip = 0L)
    val wrongR4 = f.dueBox("c", registers = Seq(ErgoValue.of(1000L), ErgoValue.of(period), ErgoValue.of(tip)))
    val wrongR6 = f.dueBox("d", registers = Seq(ErgoValue.of(1000), ErgoValue.of(period), ErgoValue.of(tip.toInt)))
    val noR6 = f.dueBox("e", registers = Seq(ErgoValue.of(1000), ErgoValue.of(period)))
    val bare = f.dueBox("f", registers = Seq.empty)
    val neverDue = f.dueBox("g", period = 0)
    val negativeTip = f.dueBox("h", tip = -1L)
    // A register the node reports but that does not decode: skipped like a wrong type, not thrown on.
    val undecodable = NodeBox(id("i"), id("i"), Parameters.OneErg, 0, 1000, f.tree, Seq.empty,
      NodeRegisters(Map("R4" -> "zz", "R5" -> ErgoValue.of(period).toHex, "R6" -> ErgoValue.of(tip).toHex)))
    f.atTree = Seq(a, wrongR4, b, wrongR6, noR6, bare, neverDue, negativeTip, undecodable)

    f.discover() shouldBe Seq(a.boxId, b.boxId)
    verify(f.api, never()).boxesWithPoolByIds(any[Seq[String]])
  }

  it should "page the index until a short page" in {
    val f = new Fixture()
    val boxes = (0 until 150).map(i => f.dueBox(s"p$i"))
    f.atTree = boxes

    f.discover() shouldBe boxes.map(_.boxId)
    f.pages.get shouldBe 2
  }

  it should "throw when the index cannot be read, so the source keeps its last pass" in {
    val f = new Fixture()
    f.atTree = Seq(f.dueBox("a"))
    f.indexDown = true

    an[IllegalStateException] should be thrownBy f.discover()
  }

  /** The configured ids have to be known before the job exists, so a throwaway fixture mints the boxes. */
  it should "also read the configured list, without listing a box twice" in {
    val probe = new Fixture()
    val a = probe.dueBox("a")
    val b = probe.dueBox("b")
    val f = new Fixture(boxIds = Seq(a.boxId, b.boxId))
    f.atTree = Seq(a)
    f.live = Seq(a, b)

    f.discover() shouldBe Seq(a.boxId, b.boxId)
  }

  "Discovery on a plain node" should "fall back to the configured list and never ask the index" in {
    val probe = new Fixture()
    val a = probe.dueBox("a")
    val spent = probe.dueBox("b")
    val stray = probe.strayBox("c")
    val f = new Fixture(indexed = false, boxIds = Seq(a.boxId, spent.boxId, stray.boxId))
    f.atTree = Seq(a, spent)
    f.live = Seq(a, stray)

    f.discover() shouldBe Seq(a.boxId)
    verify(f.api, never()).unspentBoxesByErgoTree(any[String], any[Paging], any[SortDirection], any[MempoolOptions])
  }

  it should "find nothing, and read nothing, with no list configured" in {
    val f = new Fixture(indexed = false)
    f.atTree = Seq(f.dueBox("a"))

    f.discover() shouldBe empty
    verify(f.api, never()).boxesWithPoolByIds(any[Seq[String]])
    verify(f.api, never()).unspentBoxesByErgoTree(any[String], any[Paging], any[SortDirection], any[MempoolOptions])
  }

  it should "throw when the configured boxes cannot be read" in {
    val f = new Fixture(indexed = false, boxIds = Seq(id("a")))
    when(f.api.boxesWithPoolByIds(any[Seq[String]])).thenReturn(Failure(new RuntimeException("node down")))

    an[IllegalStateException] should be thrownBy f.discover()
  }

  // ─── due ──────────────────────────────────────────────────────────────────

  "Due" should "be exact at the boundary: last beat plus period" in {
    val f = new Fixture()
    val box = f.dueBox("a", lastBeat = 1000, period = period)
    f.client.execute { ctx =>
      val input = box.toInputUTXO(ctx)
      f.job.due(input, 1000 + period - 1) shouldBe false
      f.job.due(input, 1000 + period) shouldBe true
      f.job.due(input, 1000 + period + 1) shouldBe true
      f.job.due(input, 1000 + 100 * period) shouldBe true
    }
  }

  it should "be false for a box whose registers are not a beat" in {
    val f = new Fixture()
    val malformed = f.dueBox("a", registers = Seq(ErgoValue.of(1000), ErgoValue.of(period.toLong), ErgoValue.of(tip)))
    f.client.execute { ctx =>
      f.job.due(malformed.toInputUTXO(ctx), Int.MaxValue) shouldBe false
    }
  }

  "A beat" should "read R4, R5 and R6 and nothing past them" in {
    val annotated: Seq[ErgoValue[_]] = Seq(ErgoValue.of(1000), ErgoValue.of(period), ErgoValue.of(tip), ErgoValue.of(42L))
    val short: Seq[ErgoValue[_]] = Seq(ErgoValue.of(1000), ErgoValue.of(period))
    Beat.of(annotated) shouldBe Some(Beat(1000, period, tip))
    Beat.of(short) shouldBe None
    Beat.of(Seq.empty[ErgoValue[_]]) shouldBe None
  }

  // ─── build ────────────────────────────────────────────────────────────────

  /** The successor as a signed transaction's output, read back the way the next build will read it. */
  private def successorOf(built: UpkeepJob.Built): InputUTXO = InputUTXO(built.tx.getOutputsToSpend.get(0))

  "A build" should "advance the box to a successor at the block, lighter by the tip paid to payTo" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      val box = f.dueBox("a", lastBeat = height - period, value = 2L * Parameters.OneErg,
        assets = Seq(NodeAsset(tokenId, 5L)))
      val input = box.toInputUTXO(ctx)
      f.job.due(input, height) shouldBe true

      val built = f.job.build(ctx, input, height, f.wallet.contract).getOrElse(fail("the job built nothing"))
      val outputs = built.tx.getOutputsToSpend.asScala
      outputs should have size 2

      val successor = successorOf(built)
      successor.contract.ergoTreeHex shouldBe f.tree
      successor.value shouldBe box.value - tip
      successor.tokens.map(t => (t.id.toString, t.amount)) shouldBe Seq((tokenId, 5L))
      successor.input.getCreationHeight shouldBe height
      Beat.of(successor) shouldBe Some(Beat(height, period, tip))

      val paid = outputs(1)
      Contract(paid.getErgoTree) shouldBe f.wallet.contract
      paid.getValue shouldBe tip
      paid.getCreationHeight shouldBe height
      built.capital.map(_.value) shouldBe Seq(tip)
      built.capital.head.parentTxId shouldBe built.tx.getId
      built.capital.head.outputId shouldBe paid.getId.toString

      // Spends only the box, with no fee output, and what it costs the block is measured.
      RollupExecution.signedInputIds(built.tx) shouldBe Set(box.boxId)
      Upkeep.undiscoveredInputs(built.tx, Set(box.boxId)) shouldBe empty
      built.tx.getCost should be > 0
    }
  }

  it should "pay the tip to whatever contract it is handed" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      val input = f.dueBox("a", lastBeat = height - period).toInputUTXO(ctx)
      val built = f.job.build(ctx, input, height, Contract.SIGMA_TRUE).getOrElse(fail("the job built nothing"))
      Contract(built.tx.getOutputsToSpend.get(1).getErgoTree) shouldBe Contract.SIGMA_TRUE
      built.capital.map(_.value) shouldBe Seq(tip)
    }
  }

  it should "leave a tip too small for a box of its own in the successor, and pay nothing" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      Seq(1L, 0L).foreach { small =>
        val box = f.dueBox(s"s$small", lastBeat = height - period, tip = small)
        val built = f.job.build(ctx, box.toInputUTXO(ctx), height, f.wallet.contract)
          .getOrElse(fail("the job built nothing"))
        built.tx.getOutputsToSpend.size shouldBe 1
        val successor = successorOf(built)
        successor.value shouldBe box.value
        Beat.of(successor) shouldBe Some(Beat(height, period, small))
        built.capital shouldBe empty
      }
    }
  }

  it should "build nothing for a box that can no longer pay its beat" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val height = ctx.getHeight + 1
      // The successor would keep 1000 nanoERG, under the consensus minimum for any box.
      val box = f.dueBox("a", lastBeat = height - period, value = tip + 1000L)
      f.job.due(box.toInputUTXO(ctx), height) shouldBe true
      f.job.build(ctx, box.toInputUTXO(ctx), height, f.wallet.contract) shouldBe None
    }
  }

  it should "build nothing for a box whose registers are not a beat" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val malformed = f.dueBox("a", registers = Seq(ErgoValue.of(1000L), ErgoValue.of(period), ErgoValue.of(tip)))
      f.job.build(ctx, malformed.toInputUTXO(ctx), ctx.getHeight + 1, f.wallet.contract) shouldBe None
    }
  }
}
