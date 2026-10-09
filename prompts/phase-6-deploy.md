# Phase 6: a reusable Lithos deployment for devnets

Start from branch `upkeep-adapter` at the tip and push there. Read `CLAUDE.md`. This phase is not about upkeep: it is
the tooling needed to test upkeep, and any other client feature, on a private devnet, and it is written to be reusable
by the maintainers for a new testnet. Nothing in it may change behaviour on mainnet or testnet when no deployment
override is configured.

## The goal this serves

A rig run on a wiped devnet that ends with a Lithos block the client built: its genesis transaction spending a
collateral box the client itself joined with (`emission.autoCollateralize = true`), and its package carrying an upkeep
beat. The orchestration hook (peeryard) will assert exactly that: a block whose first transaction spends a box holding
the deployment's collateral token, and which also contains the heartbeat successor. The deployer therefore has to leave
the chain in a state the client's own join and activation code accepts as it is written (`EmissionTransactions.planQueue`
and the queue and permit rules in `EmissionsCore.scala` / `QueueOwnership.scala`), and the descriptor has to carry what
the hook needs to recognise a Lithos block: the collateral token id, the emission box id and the LIT id.

## Why

The client compiles its protocol contracts from token ids and genesis coordinates that are compile-time constants
per network (`lithos-lib/src/main/scala/lfsm/LFSMHelpers.scala:93-121`: LIT, emission NFT, emission-config NFT, queue
token, collateral token, FP token, miner-dictionary token, vote token, the dictionary genesis box id and inclusion
height, and `FP_CONTROL_*` addresses at `:53`). `ProtocolContracts.forNetwork` (`app/transactions/ProtocolContracts.scala:56`)
injects them into every contract. There is no DEVNET case and no tool that creates a deployment: on a fresh chain the
candidate builder cannot find collateral, builds no genesis transaction, and never collects block transactions
(`app/mining/CandidateBuilder.scala:262-280`, `GenesisReady` is what triggers `collectBlockTxs`). So nothing that
depends on a Lithos block can be tested except on the public testnet with real LIT.

## 1. Deployment ids as a value, with a configured override

- `lithos-lib/src/main/scala/lfsm/Deployment.scala`: `final case class DeploymentIds(lit, emissionNft, emConfigNft,
  queueToken, collatToken, fpToken, mdToken, voteToken: ErgoId, mdGenesisId: String, mdGenesisHeight: Int,
  fpControlAddress: Address)` with `DeploymentIds.mainnet` and `.testnet` built from the existing constants, JSON
  read/write (the descriptor the deployer writes), and `Deployment.override: Option[DeploymentIds]` set once at startup.
- Every `LFSMHelpers.get*(network)` and the `FP_CONTROL_*` lookups answer from the override when it is set and from the
  constants otherwise. `ProtocolContracts` caches per (network, ids) so a changed override cannot serve stale contracts.
- Config: `node.deployment.file = ""` (path to a descriptor JSON; empty means none), read in `NodeConfig`, validated
  (file exists, parses, every id is 64 hex characters), logged at startup as "deployment: mainnet constants" or
  "deployment: <file>". A descriptor is refused on `node.networkType = MAINNET` unless `node.deployment.allowOnMainnet
  = true`, so a mislaid file cannot repoint a mainnet miner.
- Specs: the override reaches `ProtocolContracts` (compiled trees differ from the constants' trees); no override leaves
  every tree identical to today's (pin one hash per contract per network in the spec so a later refactor cannot drift);
  descriptor round trip; validation refusals.

## 2. The deployer

`app/tools/DeployProtocol.scala`, run as `sbt "runMain tools.DeployProtocol --node http://127.0.0.1:9153 --api-key hello
--keystore <keystore.json> --pass <pass> --network TESTNET --out deployment.json [--fund <address>:<nanoERG>:<LIT>]`.
It uses the client's own `NodeConfig`-style wiring (`NodeWallet`, `TxBuilder`, `ProtocolContracts`), never the node
wallet's payment endpoints, so the boxes are exactly what the client later reads.

Steps, each one transaction, each waited for confirmation before the next (poll `/utxo/byId`):
1. Mint the eight tokens. Amounts from the code, not guessed: queue and collateral tokens `LFSMHelpers.PROP_TOKEN_AMNT`;
   the two NFTs and the FP token 1; LIT, MD and vote token amounts as `EmissionSchedule` and the dictionary and
   emission-config contracts expect (read `lithos-lib/src/main/scala/lfsm/EmissionSchedule.scala`, the dictionary
   specs under `test/contracts/specs/dictionary/`, and `CollateralContract.mkMainnetEmConfigContract`). Name each token.
2. Set `Deployment.override` to the minted ids and compile the contracts.
3. Create the protocol boxes, shaped exactly as the client's readers expect. The authorities are the readers and the
   specs, not the fixtures: `EmissionTransactions.readEmission` / `readConfig` (`app/transactions/emissions/EmissionTransactions.scala:262-285`),
   `LFSMHelpers.getFPControlBox`, the FP control spec `test/contracts/specs/rollup/FPControlMainnetSpec.scala:57`,
   the dictionary specs, and `test/support/CollateralNodeFixtures.scala:65-130` for register layout
   (emission box: R4 current block, R5 lender set, R6 head, R7 tail, tokens NFT + queue + collateral + LIT, at the
   emission guard; config box: R4 permit params, R5 enforcer hash, R6 extension hash, R7 rollup hash, the config NFT,
   at `mkMainnetEmConfigContract`; the FP control box at `mkFPControlMainnetContract` with the FP token; the miner
   dictionary genesis at `mkMinerDictionaryContract` with the MD token). Where the fixture uses a stand-in (the config
   box's script and R6/R7), find what mainnet actually holds by reading what the readers and contracts check, and
   say in the doc comment what you chose and why.
4. Write `deployment.json`: every id, the dictionary genesis box id and its inclusion height, the FP control address,
   the emission and config box ids, the deployer address, the node's network and height. Keys named so a shell hook
   can read them with `jq` (`collatToken`, `litId`, `emissionBoxId`, ...).
5. `--fund`: pay an operator address ERG and LIT from the deployer wallet, enough for the client's own join
   (`CollateralParams.PRINCIPAL_FLOOR` plus the permit's LIT and fees, with margin), so a client with
   `autoCollateralize = true` joins the queue by itself. Say in the doc what the minimums are and where they come from.

Idempotence: refuse to run against a node whose wallet already holds a token named like these unless `--force`.
Everything logged at info with the transaction ids. Exit non-zero with the step named on any failure.

## 3. Where the rest lives

The orchestration is not this repository's: the devnet topology, the hook that brings up an indexed mining node, the
`/info` rewriting proxy appkit needs (it refuses a node reporting `"network": "devnet"`), the deployment descriptors
and the end-to-end checks live in the operator's network-infrastructure repository (peeryard, `rig/examples/lithos-*`).
This repository provides only what must compile the contracts: the override in section 1 and the deployer in section
2. Add `DEVNET.md` at the root as one page: the config keys (`node.deployment.file`, `allowOnMainnet`), the deployer's
command line and descriptor format, and a pointer that a worked devnet run is maintained outside this repository.

## 5. Specs

- Deployer: the boxes it plans parse through the client's own readers on the mocked node (`support.FakeNodeContext`),
  and `CandidateTxBuilder.loadCollateral` finds a collateral box created against the override's ids.
- Override: as in section 1.

Run `sbt -batch compile Test/compile "testOnly transactions.* lfsm.* tools.*"` with Java 17 if you can. One commit per
section. Report what you could not determine from the code about the box shapes, with the file:line you looked at.
