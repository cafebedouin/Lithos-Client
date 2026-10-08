# Phase 2: the reference job, a self-describing due-job box

Read `CLAUDE.md`, `UPKEEP-BRIEF.md` (sections 3 and 4), and the phase-1 code under
`app/transactions/upkeep/`. Then read how this repository stores, loads and tests contracts:
`lithos-lib/src/main/scala/lfsm/ScriptGenerator.scala`, two `.ergo` files under
`lithos-lib/src/main/resources/collateral/`, `test/contracts/specs/harness/ContractSpecBase.scala`,
and one spec under `test/contracts/specs/collateral/` or `emission/` that proves a refusal through
the interpreter. Also read `lithos-lib/src/main/scala/node/NodeApi.scala` and the `NodeIndexerApi`
trait inside it, to decide how `HeartbeatJob` discovers boxes on an indexed node and what it does on
one without the extra index.

Build:
1. `lithos-lib/src/main/resources/upkeep/DueJob.ergo` exactly as section 4 of the brief specifies
   (R4 last-beat height, R5 period, R6 tip; anyone may advance once due; successor at `OUTPUTS(0)`
   with the same proposition and tokens, `R4 == HEIGHT`, R5/R6 unchanged, `value >= SELF.value - R6`;
   the remainder unconstrained; no owner path). A header comment states the rule in words and the
   way the box ends (it ages out through storage rent when it can no longer pay a beat).
2. A loader for the `upkeep` group in `ScriptGenerator` following the existing ones.
3. `test/contracts/specs/upkeep/DueJobSpec.scala` extending `ContractSpecBase`, proving: a due box
   advances; one block early is refused; wrong R4, changed R5, changed R6, dropped token, value under
   `SELF.value - R6`, and a successor at a different script are each refused. Each failing case must
   fail for the reason named (assert on the interpreter's refusal, not on an exception message).
4. `app/transactions/upkeep/jobs/HeartbeatJob.scala` implementing `UpkeepJob`: discovery by the
   contract's ErgoTree (plus a configured list of box ids or a token id as the fallback, whichever
   your reading of `NodeApi` makes cheap and honest; document which node features it needs), `due`
   by the register rule, `build` producing the successor and a tip output to `payTo`, declared as a
   `CapitalEntry`. Register it in `UpkeepRegistry` under the name `heartbeat`, disabled by default,
   with a commented config block in `application.conf`.
5. `test/transactions/upkeep/HeartbeatJobSpec.scala`: discovery parses registers correctly; a box with
   malformed registers is skipped, not thrown on; `due` is exact at the boundary; the built successor
   carries the right registers, tokens and value and the tip is at most R6.

Run `sbt -batch "testOnly contracts.specs.upkeep.* transactions.upkeep.*"` with Java 17 if you can.
Commit as one commit, "Add the heartbeat job and the DueJob contract it advances". Report as in
phase 1, and add: what a node needs (extra index or not) for discovery to work.
