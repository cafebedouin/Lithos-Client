package transactions.upkeep

import node.MutationConversions._
import node.NodeApi
import node.model._
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.Parameters
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import scorex.crypto.hash.Blake2b256
import sigma.ast.ErgoTree
import support.{CanonicalNodeBox, FakeNodeContext}
import transactions.engine.execution.RollupExecution
import transactions.upkeep.jobs.{HeartbeatJob, KeepAliveJob}
import transactions.upkeep.jobs.KeepAliveJob.Terms
import work.lithos.mutations.{Contract, InputUTXO}

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

/**
 * The KeepAlive job against a mocked node. The trees are KeepAliveAddress.ergo as compiled by node
 * 6.0.7 (tree version 1) for two owners on devnet terms and one on mainnet terms; signing a build
 * offline evaluates that script, so a build that signs is one the address's script accepts. The
 * script's own refusals (a token dropped, a bounty too large, another owner, double satisfaction,
 * a stale successor) are proven against a node in skunkyard `skunks/keepalive`.
 */
class KeepAliveJobSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  import KeepAliveJobSpec._

  private val token1 = "ab" * 32
  private val token2 = "cd" * 32

  private def id(seed: String): String = (seed.getBytes("UTF-8").map(b => f"$b%02x").mkString * 64).take(64)

  private class Fixture(minBounty: Long = KeepAliveJob.DefaultMinBounty) {
    val api: NodeApi = mock[NodeApi]
    val (nodeContext, _, wallet) = FakeNodeContext(api, numAddresses = 1)
    val client = nodeContext.getClient
    val job = new KeepAliveJob(minBounty)
    @volatile var indexed: Seq[NodeBox] = Seq.empty

    when(api.indexerEnabled).thenReturn(true)
    when(api.unspentBoxesByTemplateHash(any[String], any[Paging], any[SortDirection], any[MempoolOptions]))
      .thenAnswer { inv =>
        val asked = inv.getArgument[String](0)
        val paging = inv.getArgument[Paging](1)
        val hits = if (asked == KeepAliveJob.TemplateHash) indexed else Seq.empty
        Success(hits.slice(paging.offset, paging.offset + paging.limit).map(b => IndexedBox(b, "", b.creationHeight, 1L)))
      }

    def box(seed: String, tree: String, value: Long, created: Int, assets: Seq[NodeAsset] = Seq.empty): NodeBox =
      CanonicalNodeBox(id(seed), id(seed), value, 0, created, tree, assets)

    def discover(): Seq[String] = client.execute(ctx => job.discover(ctx, api, ctx.getHeight))
  }

  // ─── the template and its terms ───────────────────────────────────────────

  "The template hash" should "be Blake2b256 of every pinned tree's template, whatever the owner or terms" in {
    Seq(DevnetOwnerTree, DevnetOtherTree, MainnetTree).foreach { hex =>
      Hex.toHexString(Blake2b256.hash(ErgoTree.fromHex(hex).template)) shouldBe KeepAliveJob.TemplateHash
    }
  }

  "Terms" should "be read from the tree's own constants" in {
    Terms.of(DevnetOwnerTree) shouldBe Some(Terms(DevnetOwner, 40, 20, 500000L, 2000000L, 10))
    Terms.of(MainnetTree).map(t => (t.period, t.window, t.perInput, t.refresh, t.slack)) shouldBe
      Some((1051200, 21600, 500000L, 2000000L, 10))
  }

  it should "not be found in another script" in {
    Terms.of(HeartbeatJob.TreeHex) shouldBe None
  }

  // ─── discovery and due ────────────────────────────────────────────────────

  "Discovery" should "report every box under the template, largest first per address" in {
    val f = new Fixture()
    val h = f.client.execute(_.getHeight)
    val a1 = f.box("a1", DevnetOwnerTree, Parameters.OneErg / 20, h - 5)
    val a2 = f.box("a2", DevnetOwnerTree, Parameters.OneErg / 1000, h - 5)
    val b1 = f.box("b1", DevnetOtherTree, Parameters.OneErg / 1000, h - 5)
    f.indexed = Seq(a2, b1, a1)
    f.discover().toSet shouldBe Set(a1.boxId, a2.boxId, b1.boxId)
  }

  "Due" should "hold for an address's largest box when there is another to merge, and only for it" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val h = ctx.getHeight
      val main = f.box("m", DevnetOwnerTree, Parameters.OneErg / 20, h - 5)
      val small = f.box("s", DevnetOwnerTree, Parameters.OneErg / 1000, h - 5)
      f.indexed = Seq(main, small)
      f.job.discover(ctx, f.api, h)
      f.job.due(main.toInputUTXO(ctx), h + 1) shouldBe true
      f.job.due(small.toInputUTXO(ctx), h + 1) shouldBe false
    }
  }

  it should "hold for a lone box only from WINDOW blocks before PERIOD" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val h = ctx.getHeight
      val lone = f.box("l", DevnetOwnerTree, Parameters.OneErg / 20, h - 30)
      f.indexed = Seq(lone)
      f.job.discover(ctx, f.api, h)
      val input = lone.toInputUTXO(ctx)
      f.job.due(input, lone.creationHeight + 19) shouldBe false
      f.job.due(input, lone.creationHeight + 20) shouldBe true
    }
  }

  // ─── build ────────────────────────────────────────────────────────────────

  "A merge" should "spend every box of the address into one, every token kept, the bounty to payTo" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val h = ctx.getHeight
      val main = f.box("m", DevnetOwnerTree, 50000000L, h - 5, Seq(NodeAsset(token1, 1L)))
      val p2 = f.box("p2", DevnetOwnerTree, 1000000L, h - 5, Seq(NodeAsset(token1, 2L)))
      val p3 = f.box("p3", DevnetOwnerTree, 1000000L, h - 5, Seq(NodeAsset(token1, 3L), NodeAsset(token2, 7L)))
      f.indexed = Seq(main, p2, p3)
      f.job.discover(ctx, f.api, h)
      val built = f.job.build(main.toInputUTXO(ctx), BuildContext(ctx, h + 1, f.wallet.contract))
        .getOrElse(fail("the job built nothing"))
      RollupExecution.signedInputIds(built.tx) shouldBe Set(main.boxId, p2.boxId, p3.boxId)
      val outs = built.tx.getOutputsToSpend.asScala
      outs should have size 2
      val merged = InputUTXO(outs.head)
      merged.contract.ergoTreeHex shouldBe DevnetOwnerTree
      merged.tokens.map(t => t.id.toString -> t.amount).toMap shouldBe Map(token1 -> 6L, token2 -> 7L)
      val bounty = math.min(3 * 500000L, 52000000L - 50000000L)
      merged.value shouldBe 52000000L - bounty
      merged.input.getCreationHeight shouldBe h + 1
      Contract(outs(1).getErgoTree) shouldBe f.wallet.contract
      outs(1).getValue shouldBe bounty
      built.capital.map(_.value) shouldBe Seq(bounty)
    }
  }

  it should "never take from the largest box: dust merged with it pays only its own value" in {
    val f = new Fixture(minBounty = 0L)
    f.client.execute { ctx =>
      val h = ctx.getHeight
      val main = f.box("m", DevnetOwnerTree, 50000000L, h - 5, Seq(NodeAsset(token1, 1L)))
      val dust = f.box("d", DevnetOwnerTree, 300000L, h - 5)
      f.indexed = Seq(main, dust)
      f.job.discover(ctx, f.api, h)
      val built = f.job.build(main.toInputUTXO(ctx), BuildContext(ctx, h + 1, f.wallet.contract))
        .getOrElse(fail("the job built nothing"))
      val merged = InputUTXO(built.tx.getOutputsToSpend.get(0))
      merged.value should be >= main.value
      built.capital.map(_.value).sum shouldBe 300000L
    }
  }

  it should "not be built when the bounty is under minBounty" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val h = ctx.getHeight
      val main = f.box("m", DevnetOwnerTree, 50000000L, h - 5)
      val dust = f.box("d", DevnetOwnerTree, 300000L, h - 5)
      f.indexed = Seq(main, dust)
      f.job.discover(ctx, f.api, h)
      f.job.build(main.toInputUTXO(ctx), BuildContext(ctx, h + 1, f.wallet.contract)) shouldBe None
    }
  }

  "A lone refresh" should "recreate the box in its window, for at most REFRESH" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val h = ctx.getHeight
      val lone = f.box("l", DevnetOwnerTree, 50000000L, h - 25, Seq(NodeAsset(token1, 4L)))
      f.indexed = Seq(lone)
      f.job.discover(ctx, f.api, h)
      val built = f.job.build(lone.toInputUTXO(ctx), BuildContext(ctx, h + 1, f.wallet.contract))
        .getOrElse(fail("the job built nothing"))
      val fresh = InputUTXO(built.tx.getOutputsToSpend.get(0))
      fresh.value shouldBe 50000000L - 2000000L
      fresh.tokens.map(t => t.id.toString -> t.amount) shouldBe Seq(token1 -> 4L)
      fresh.input.getCreationHeight shouldBe h + 1
    }
  }

  it should "not sign before the window: the script refuses it" in {
    val f = new Fixture()
    f.client.execute { ctx =>
      val h = ctx.getHeight
      val lone = f.box("l", DevnetOwnerTree, 50000000L, h - 5)
      f.indexed = Seq(lone)
      f.job.discover(ctx, f.api, h)
      Try(f.job.build(lone.toInputUTXO(ctx), BuildContext(ctx, h + 1, f.wallet.contract))).isFailure shouldBe true
    }
  }
}

object KeepAliveJobSpec {
  /** The devnet owner's key (a peeryard devnet node's wallet), constant 1 of the devnet owner tree. */
  val DevnetOwner = "038b0f29a60fa8d7e1aeafbe512288a6c6bc696547bbf8247db23c95e83014513c"
  val DevnetOwnerTree: String = "198c030f05000e21038b0f29a60fa8d7e1aeafbe512288a6c6bc696547bbf8247db23c95e83014513c04040500045004" +
    "28050005c0843d05000504058092f4010414040004080100d802d601e30004d602d901023c630eb0db63088c72020173" +
    "00d90104414d0ed802d6068c720402d6078c72040195938c7206018c7202029a72078c7206027207eb02cdee7301d195" +
    "e67201d806d603c2a7d604b5a4d901046393c272047203d605b17204d6069272057302d607b2a5e4720100d608b07204" +
    "7303d9010841639a8c720801c18c72080296830601ec720692a3999a8cc7a7017304730593c272077203af7204d90109" +
    "63afdb63087209d9010b4d0ed801d60d8c720b0192da72020186027207720db072047306d9010e41639a8c720e01da72" +
    "020186028c720e02720d92c17207997208957206a19c7e7205057307997208b072047308d901094163a28c720901c18c" +
    "720902a19dc1a77309730a928cc772070199a3730b95720690b1c37207b07204730cd9010940639a8c720901b1c38c72" +
    "090290b1c372079ab1c3a7730d730e"
  val DevnetOtherTree: String = "198c030f05000e210279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179804040500045004" +
    "28050005c0843d05000504058092f4010414040004080100d802d601e30004d602d901023c630eb0db63088c72020173" +
    "00d90104414d0ed802d6068c720402d6078c72040195938c7206018c7202029a72078c7206027207eb02cdee7301d195" +
    "e67201d806d603c2a7d604b5a4d901046393c272047203d605b17204d6069272057302d607b2a5e4720100d608b07204" +
    "7303d9010841639a8c720801c18c72080296830601ec720692a3999a8cc7a7017304730593c272077203af7204d90109" +
    "63afdb63087209d9010b4d0ed801d60d8c720b0192da72020186027207720db072047306d9010e41639a8c720e01da72" +
    "020186028c720e02720d92c17207997208957206a19c7e7205057307997208b072047308d901094163a28c720901c18c" +
    "720902a19dc1a77309730a928cc772070199a3730b95720690b1c37207b07204730cd9010940639a8c720901b1c38c72" +
    "090290b1c372079ab1c3a7730d730e"
  val MainnetTree: String = "1991030f05000e210279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798040405000480a9" +
    "800104c0d102050005c0843d05000504058092f4010414040004080100d802d601e30004d602d901023c630eb0db6308" +
    "8c7202017300d90104414d0ed802d6068c720402d6078c72040195938c7206018c7202029a72078c7206027207eb02cd" +
    "ee7301d195e67201d806d603c2a7d604b5a4d901046393c272047203d605b17204d6069272057302d607b2a5e4720100" +
    "d608b072047303d9010841639a8c720801c18c72080296830601ec720692a3999a8cc7a7017304730593c272077203af" +
    "7204d9010963afdb63087209d9010b4d0ed801d60d8c720b0192da72020186027207720db072047306d9010e41639a8c" +
    "720e01da72020186028c720e02720d92c17207997208957206a19c7e7205057307997208b072047308d901094163a28c" +
    "720901c18c720902a19dc1a77309730a928cc772070199a3730b95720690b1c37207b07204730cd9010940639a8c7209" +
    "01b1c38c72090290b1c372079ab1c3a7730d730e"
}
