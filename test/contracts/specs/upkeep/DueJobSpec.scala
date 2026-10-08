package contracts.specs.upkeep

import contracts.specs.harness.ContractSpecBase
import lfsm.contracts.UpkeepContracts
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, TxBuilder, UTXO}

object DueJobSpec {

  /** Compiled once; the tree takes no constants, so one compile serves every property. */
  private var cache: Option[Contract] = None

  def compiled(ctx: BlockchainContext): Contract = synchronized {
    cache.getOrElse {
      val contract = UpkeepContracts.mkDueJobContract(ctx)
      cache = Some(contract)
      contract
    }
  }
}

/**
 * DueJob.ergo — one property per named condition in the contract, plus the shape of what it
 * leaves free.
 *
 * Every negative is differential: it takes the beat the first property proves accepted and changes
 * exactly the field its condition reads, with the transaction rebalanced so it still builds. The
 * refusal is then the interpreter's, at signing, and the only thing that differs from the accepted
 * spend is the one the property names. `rejectsAtSigning` holds the spec to that: a perturbation
 * that merely unbalanced the transaction would fail at build and be reported as such.
 *
 * The beat is at HEIGHT = tip height + 1, the next block, through a preHeader: the script compares
 * against HEIGHT exactly, and the mocked context's own height is one below where a candidate's
 * transactions are validated. The box is funded far above a lifetime of beats, so no negative is
 * confounded by the consensus minimum.
 */
class DueJobSpec extends AnyPropSpec with ContractSpecBase {

  private val period: Int = 10
  private val tip: Long = Parameters.OneErg / 100
  private val token: Token = Token(ErgoId.create("ab" * 32), 7L)

  private def dueJob(ctx: BlockchainContext): Contract = DueJobSpec.compiled(ctx)

  private def regs(lastBeat: Int, period: Int, tip: Long): Seq[ErgoValue[_]] =
    Seq(ErgoValue.of(lastBeat), ErgoValue.of(period), ErgoValue.of(tip))

  /**
   * One beat of one box: the box as it stands, due exactly at `height`, and the two outputs the
   * executor is expected to make. The tip goes to the miner; the contract does not care where.
   */
  private case class Beat(ctx: BlockchainContext,
                          box: InputUTXO,
                          successor: UTXO,
                          tipOut: UTXO,
                          height: Int,
                          lastBeat: Int,
                          prover: ErgoProver)

  private def beat(ctx: BlockchainContext, tokens: Seq[Token] = Seq(token)): Beat = {
    val height = ctx.getHeight + 1
    val lastBeat = height - period
    val standing = UTXO(dueJob(ctx), boxValue, tokens, regs(lastBeat, period, tip)).setCreationHeight(lastBeat)
    val successor = UTXO(dueJob(ctx), boxValue - tip, tokens, regs(height, period, tip)).setCreationHeight(height)
    val tipOut = UTXO(contractOf(miner(ctx)), tip).setCreationHeight(height)
    Beat(ctx, inputAt(standing, ctx, 0), successor, tipOut, height, lastBeat, miner(ctx))
  }

  /** Fee-less and balanced to zero change, as the client's own block transactions are. */
  private def beatTx(b: Beat)(outputs: Seq[UTXO] = Seq(b.successor, b.tipOut),
                              height: Int = b.height): UnsignedTransaction =
    TxBuilder(b.ctx)
      .setInputs(b.box)
      .setOutputs(outputs: _*)
      .setPreHeader(b.ctx.createPreHeader().height(height).build())
      .buildTx(0L, b.prover.getAddress)

  // ─── the beat ─────────────────────────────────────────────────────────────

  property("beat: a due box advances to a successor stamped with the height, lighter by the tip") {
    withCtx { ctx =>
      val b = beat(ctx)
      val signed = accepts(b.prover, beatTx(b)())
      signed.getOutputsToSpend.size shouldBe 2
      signed.getOutputsToSpend.get(0).getValue shouldBe boxValue - tip
      signed.getOutputsToSpend.get(1).getValue shouldBe tip
    }
  }

  /** Keyless end to end: the script asks for no signature, so a prover holding no secret suffices. */
  property("beat: a prover holding no secret advances the box") {
    withCtx { ctx =>
      val b = beat(ctx)
      accepts(ctx.newProverBuilder().build(), beatTx(b)())
    }
  }

  property("beat: a box carrying no tokens advances the same way") {
    withCtx { ctx =>
      val b = beat(ctx, tokens = Seq.empty)
      accepts(b.prover, beatTx(b)())
    }
  }

  property("due: the same beat one block early is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      val early = b.height - 1
      early shouldBe b.lastBeat + period - 1
      // R4 follows HEIGHT, so `due` is the only condition that changes.
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(4, ErgoValue.of(early)).setCreationHeight(early),
          b.tipOut.setCreationHeight(early)),
        height = early))
    }
  }

  property("due: a box overdue by many blocks still advances") {
    withCtx { ctx =>
      val b = beat(ctx)
      val late = b.height + 5 * period
      accepts(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(4, ErgoValue.of(late)).setCreationHeight(late),
          b.tipOut.setCreationHeight(late)),
        height = late))
    }
  }

  // ─── the successor ────────────────────────────────────────────────────────

  property("beatStamped: a successor whose R4 is not HEIGHT is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(4, ErgoValue.of(b.height - 1)), b.tipOut)))
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(4, ErgoValue.of(b.height + 1)), b.tipOut)))
    }
  }

  property("periodKept: a successor with a changed R5 is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(5, ErgoValue.of(period + 1)), b.tipOut)))
    }
  }

  /** Even a smaller tip: the terms are the creator's, and an executor may not rewrite them either way. */
  property("tipKept: a successor with a changed R6 is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(6, ErgoValue.of(tip - 1L)), b.tipOut)))
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.withRegNum(6, ErgoValue.of(tip + 1L)), b.tipOut)))
    }
  }

  property("sameTokens: a successor that dropped the box's token is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      // The token has to land somewhere for the transaction to build; the tip output takes it.
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.setTokens(), b.tipOut.setTokens(token))))
    }
  }

  property("valueKept: a successor keeping less than value - R6 is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.subValue(1L), b.tipOut.addValue(1L))))
    }
  }

  property("sameScript: a successor under another script is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(
        outputs = Seq(b.successor.setContract(Contract.SIGMA_TRUE), b.tipOut)))
    }
  }

  /** The successor is OUTPUTS(0) by position: a correct one anywhere else does not count. */
  property("successor: the tip output ahead of the successor is refused") {
    withCtx { ctx =>
      val b = beat(ctx)
      rejectsAtSigning(b.prover, beatTx(b)(outputs = Seq(b.tipOut, b.successor)))
    }
  }

  // ─── what the contract leaves free ────────────────────────────────────────

  property("remainder: the tip may go to anyone") {
    withCtx { ctx =>
      val b = beat(ctx)
      val elsewhere = b.tipOut.setContract(contractOf(proverWith(ctx, otherSecret)))
      accepts(b.prover, beatTx(b)(outputs = Seq(b.successor, elsewhere)))
    }
  }

  property("remainder: a successor keeping more than value - R6 advances, with a smaller tip") {
    withCtx { ctx =>
      val b = beat(ctx)
      accepts(b.prover, beatTx(b)(
        outputs = Seq(b.successor.addValue(tip / 2), b.tipOut.subValue(tip / 2))))
    }
  }

  /** A beat for nothing: how a tip too small for a box of its own is still paid, by not being taken. */
  property("remainder: a successor keeping the whole value advances with no tip output") {
    withCtx { ctx =>
      val b = beat(ctx)
      accepts(b.prover, beatTx(b)(outputs = Seq(b.successor.setValue(boxValue))))
    }
  }

  property("remainder: a successor may carry registers past R6") {
    withCtx { ctx =>
      val b = beat(ctx)
      val annotated = b.successor.setRegs((b.successor.registers :+ ErgoValue.of(42L)): _*)
      accepts(b.prover, beatTx(b)(outputs = Seq(annotated, b.tipOut)))
    }
  }
}
