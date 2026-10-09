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

import scala.collection.JavaConverters._
import scala.util.Success

/**
 * The heartbeat's own rule against a mocked node: which boxes at its script it maintains, what
 * `due` says at the boundary, and what its plan produces once signed. Discovery, paging, the
 * configured list and assembly are every script job's and [[ScriptJobSpec]]'s to prove; the
 * contract's own rules are [[contracts.specs.upkeep.DueJobSpec]]'s. Here the successor is checked
 * against those rules field by field, and signing it offline shows the plan is one the keyless
 * prover can sign.
 */
class HeartbeatJobSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val period: Int = 10
  private val tip: Long = Parameters.OneErg / 100
  private val tokenId: String = "ab" * 32

  /** A box id from any seed: the seed's bytes as hex, repeated, so `CanonicalNodeBox` can decode it. */
  private def id(seed: String): String =
    (seed.getBytes("UTF-8").map(b => f"$b%02x").mkString * 64).take(64)

  /** An indexed node, so discovery asks the index for every box at the script. */
  private class Fixture {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val client = nodeContext.getClient
    val job = new HeartbeatJob(Seq.empty)
    val tree: String = client.execute(ctx => HeartbeatJob.contract(ctx.getNetworkType).ergoTreeHex)

    /** What the index holds. */
    @volatile var atTree: Seq[NodeBox] = Seq.empty

    when(api.indexerEnabled).thenReturn(true)
    when(api.unspentBoxesByErgoTree(any[String], any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        val asked = inv.getArgument[String](0)
        val paging = inv.getArgument[Paging](1)
        Success(atTree.filter(_.ergoTree == asked).slice(paging.offset, paging.offset + paging.limit)
          .map(box => IndexedBox(box, "", box.creationHeight, 1L)))
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
  }

  // ─── discovery ────────────────────────────────────────────────────────────

  "Discovery" should "keep every box at the contract whose registers are a beat, and skip the rest" in {
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

      val built = f.job.build(input, BuildContext(ctx, height, f.wallet.contract)).getOrElse(fail("the job built nothing"))
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
      val built = f.job.build(input, BuildContext(ctx, height, Contract.SIGMA_TRUE)).getOrElse(fail("the job built nothing"))
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
        val built = f.job.build(box.toInputUTXO(ctx), BuildContext(ctx, height, f.wallet.contract))
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
      f.job.build(box.toInputUTXO(ctx), BuildContext(ctx, height, f.wallet.contract)) shouldBe None
    }
  }

  it should "build nothing for a box whose registers are not a beat" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val malformed = f.dueBox("a", registers = Seq(ErgoValue.of(1000L), ErgoValue.of(period), ErgoValue.of(tip)))
      f.job.build(malformed.toInputUTXO(ctx), BuildContext(ctx, ctx.getHeight + 1, f.wallet.contract)) shouldBe None
    }
  }
}
