# Running Lithos on a private chain

The client compiles its protocol contracts from token ids that are fixed per network. On a chain with
no Lithos deployment it finds no collateral box, builds no genesis transaction, and never mines a
Lithos block. This page covers the two pieces that change that: a deployment override the client
reads at startup, and a deployer that creates the deployment. Neither changes anything on mainnet or
testnet unless a descriptor is configured.

## Client configuration

```hocon
node {
  networkType = "TESTNET"        # appkit has no DEVNET; a devnet node is addressed as TESTNET
  deployment {
    file = "/path/to/deployment.json"   # empty (the default) uses the network's own deployment
    allowOnMainnet = false              # a descriptor on MAINNET is refused unless this is true
  }
}
```

At startup the client logs either `deployment: testnet constants` (or mainnet) or
`deployment: <file>`. The file must exist, parse, name 64-hex ids, and name the same network as
`node.networkType`; otherwise the client refuses to start and says which key is wrong.

On a fresh chain also set `sync.startHeight` at or below the descriptor's `mdGenesisHeight`, and
`emission.autoCollateralize = true` if the client should join the queue with its own ERG and LIT.
The node must run with `ergo.node.extraIndex = true`.

## The deployer

```sh
sbt "runMain tools.DeployProtocol --node http://127.0.0.1:9153 --api-key hello \
  --keystore <keystore.json> --pass <pass> --network TESTNET --out deployment.json \
  [--fund <address>:<nanoERG>:<LIT base units>]... [--force] [--timeout-seconds 1800]"
```

It signs with the keystore's EIP-3 index 0 key and spends that key's token-free boxes and matured
coinbase boxes. Each step is one transaction and waits for confirmation (something must be mining the
chain):

1. Mint the eight tokens, one transaction each: LIT (1,000,000,000 at 9 decimals), the emission NFT,
   the emission config NFT, the queue and collateral proposition tokens (`Long.MaxValue` each), the FP
   control NFT, the miner dictionary NFT, and five vote tokens.
2. Compile the contracts against the minted ids.
3. Create the emission box (825,000,000 LIT, empty queue and active set), the emission config box
   (permit params, enforcer hash, holding hash, governed by the vote tokens), the FP control box and
   the miner dictionary genesis box, in one transaction. The rest of the LIT and the vote tokens stay
   with the deployer.
4. Write the descriptor.
5. With `--fund`, pay each operator address its ERG and LIT in one transaction.

A wallet that already holds tokens named like these is refused unless `--force`. Any failure exits
non-zero naming the step.

### Funding a self-joining operator

One join needs at least 2.917 ERG (`CollateralParams.PRINCIPAL_FLOOR` 2.915 ERG, plus the join's fee
and a min-value change box) and 2,000 LIT (`LFSMHelpers.PERMIT_FLOOR`, the permit an empty queue
demands; each queued box raises it by 20 LIT). The deployer refuses less. Fund generously, e.g.
`--fund <address>:20000000000:20000000000000` (20 ERG, 20,000 LIT), so `maxJoinsPerRun` joins fit.

## Descriptor

```json
{
  "network": "TESTNET",
  "litId": "...", "emissionNft": "...", "emConfigNft": "...", "queueToken": "...",
  "collatToken": "...", "fpToken": "...", "mdToken": "...", "voteToken": "...",
  "mdGenesisId": "...", "mdGenesisHeight": 123,
  "fpControlAddress": "...",
  "emissionBoxId": "...", "configBoxId": "...", "fpControlBoxId": "...",
  "deployerAddress": "...", "height": 125
}
```

Ids are 64 hex characters. A Lithos block is one whose first transaction spends a box holding
`collatToken`. The box ids are informational: the client finds every box by its token.

## Worked runs

The devnet topology, the indexed mining node, the `/info` rewriting proxy appkit needs, and the
end-to-end checks are kept outside this repository, in the operator's network-infrastructure
repository (`rig/examples/lithos-*`).
