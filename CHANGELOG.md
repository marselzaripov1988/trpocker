# Changelog

All notable changes to TruHoldem will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### 🔒 KYC media: encryption at rest + GDPR retention/erasure
- **Encryption at rest**: KYC verification videos are encrypted with **AES-256-GCM** (new pure-JDK `KycCrypto`,
  `[12-byte IV][ciphertext+tag]` on disk) when `app.payments.kyc-encryption-key` (base64 AES key) is set; the
  key is never stored with the data, GCM detects tampering, and reads transparently decrypt for ADMIN review.
  A per-document `encrypted` flag (changeset `06-kyc-document-encryption`) lets the system read both plaintext
  and encrypted files (graceful key-rollout). The sha-256 is over the plaintext either way.
- **GDPR retention**: `KycRetentionScheduler` periodically deletes KYC media (file + metadata) older than
  `app.payments.kyc-retention-days` (default 30; 0 = never). Idempotent (`deleteIfExists`), so it is safe on
  every cluster node.
- **GDPR right-to-erasure**: `DELETE /v1/admin/wallet/kyc/{userId}/documents` removes all of a user's KYC
  media on demand.
- Verified: encryption round-trip (on-disk bytes ≠ plaintext, length = plaintext + 28; decrypts back),
  retention purge and erasure delete file + row; full suite green (1023) + a fresh-Postgres cluster boots with
  all **seven** changesets applied and `ddl-auto=validate` passing on both nodes.

### 🪪 KYC verification video upload (user + passport)
- Users can upload a **KYC verification video** (e.g. holding their passport) at
  `POST /v1/wallet/kyc/document` (multipart); uploading moves their KYC to `PENDING`. New
  `KycVerificationService` validates type (`video/*`) and size (`app.payments.kyc-max-upload-bytes`,
  default 50 MB), stores the **file bytes on disk** under `app.payments.kyc-storage-dir` (keyed by a random
  name — never the client filename), and persists only **metadata** in the DB (`kyc_documents`: content type,
  size, sha-256, storage key, uploaded-at) — no LOB columns.
- Moderators (ADMIN) review via `GET /v1/admin/wallet/kyc/{userId}/document` (streams the latest video) and
  decide with `POST /v1/admin/wallet/kyc/{userId}/decision` (`VERIFIED`/`REJECTED`). Multipart limits raised
  to ~50 MB (`spring.servlet.multipart.*`).
- Sensitive biometric PII: the bytes are served only to ADMIN; the on-disk name is opaque. **In a cluster the
  storage dir must be a shared volume** (documented) so any node can read what another received; object
  storage is a follow-up. Liquibase changeset `05-kyc-documents` (Postgres-only; H2 regenerates from the
  entity). Verified: full suite green (1023) + a fresh-Postgres cluster boots with all **six** changesets
  applied and `ddl-auto=validate` passing on both nodes.

### 💰 Watch-only deposit detection (address → user → credit)
- New ingestion path for the offline/watch-only pool: a node/indexer that scans the pooled addresses posts a
  detected deposit **keyed by address** (it doesn't know the user) to a new secret-guarded webhook
  `POST /internal/wallet/deposit-by-address` (`{asset, address, txId, amount, confirmations}`).
  `DepositIngestionService` resolves the owning user from the pool (`DepositAddressPoolService.assignedUser`)
  and credits **idempotently by tx id** via the existing `WalletService.creditOnChainDeposit`.
- **Min-confirmations gate**: credit is withheld until `app.payments.min-confirmations` (default 1) is reached
  (`PENDING_CONFIRMATIONS`); deposits to an unknown/unassigned address are ignored (`UNKNOWN_ADDRESS`), and a
  redelivered tx is a no-op (`DUPLICATE`). The endpoint always returns 200 with the outcome so the watcher
  need not retry. No schema change (config + existing tables).
- This complements the existing `POST /internal/wallet/deposit` (which is keyed by `userId`, for custodial
  gateways). Verified end-to-end: assigned-address credit, idempotency, confirmation gate, unassigned/unknown
  ignored; full suite green (1023). The actual chain watcher (node/indexer/explorer feed) is an external,
  documented follow-up.

### 💰 BTC Taproot (`bc1p…`) addresses for the offline pool
- The offline pool now also supports **Taproot** (P2TR, key-path-only, `bc1p…`). New pure-Java `TaprootKeys`
  implements BIP-341: x-only internal key from `d·G`, `lift_x` to the even-Y point, tweak
  `t = tagged_hash("TapTweak", P_x)`, output key `Q = P + t·G`, then **bech32m** (BIP-350) of `Q_x` as
  witness v1. `Bech32` was generalised to switch between bech32 (v0) and bech32m (v1+) by the witness
  version. The secp256k1 primitives in `EthKeys` (point add/mul, field/order constants) are now
  package-private so the crypto classes can share them — no new dependency.
- `OfflineDepositPoolGenerator` gains `--btc-style=taproot`; BTC import validation already accepts any of
  P2PKH / P2WPKH / P2TR via `BtcKeys.isValidAddress`. No schema change.
- Verified against an independent BIP-341 implementation (privkey 1 →
  `bc1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5sspknck9`), bech32m encode/decode round-trip, and
  rejection of v0/cross-type input; full suite green (1023). Bitcoin deposit addresses now cover all three
  mainstream formats (legacy, SegWit, Taproot).

### 💰 BTC native SegWit (bech32 `bc1q…`) addresses for the offline pool
- The offline pool now supports **native SegWit v0 P2WPKH** (`bc1q…`) Bitcoin addresses alongside legacy
  P2PKH — bech32 addresses are cheaper to spend from and the de-facto modern standard. New pure-Java `Bech32`
  (BIP-173) encoder/validator + `BtcKeys.p2wpkhAddress` (same `HASH160`, bech32-encoded). No new dependency.
- `OfflineDepositPoolGenerator` gains `--btc-style=p2pkh|bech32` (default p2pkh); BTC import validation now
  accepts **either** format (`BtcKeys.isValidAddress`). No schema change.
- Verified against the canonical BIP-173 vector (`bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4`), encode/decode
  round-trip, and rejection of wrong-hrp/tampered/mixed-case input; full suite green (1019). Taproot
  (`bc1p…`, bech32m) remains a follow-up.

### 💰 BTC (legacy P2PKH) addresses for the offline pool
- The offline generator + pool now support **Bitcoin** (legacy P2PKH `1…` addresses). A BTC address uses the
  compressed secp256k1 public key (reusing `EthKeys.publicKeyBytes`), `HASH160 = RIPEMD160(SHA-256(pubkey))`,
  and Base58Check with the `0x00` version byte. Two new pure-Java primitives — `Ripemd160` (the JDK has no
  provider) and `BtcKeys` — plus shared `Base58.encodeChecked`/`verifyChecked` (now reused by `TronKeys`). No
  new dependency; SegWit/bech32 + Taproot are documented follow-ups (P2PKH is universally accepted).
- `OfflineDepositPoolGenerator` emits BTC addresses (`--asset=BTC`) under a separate derivation label, so the
  BTC key set never overlaps the ETH/TRON ones; import validation checks P2PKH version + Base58Check. No
  schema change — `BTC` was already a known asset.
- Verified: `Ripemd160` against canonical vectors; `BtcKeys` against an independent implementation (privkey 1
  → `1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH`) and the real Bitcoin genesis address; full suite green (1014).

### 💰 USDT-TRC20 (TRON) addresses for the offline pool
- The offline generator + pool now support **TRON (TRC-20)** alongside the Ethereum family — TRC-20 is the
  most common crypto deposit method on poker/casino sites. A TRON account reuses the same secp256k1 + Keccak-256
  primitives as Ethereum (`EthKeys.addressBytesFromPrivateKey`); only the encoding differs: new pure-Java
  `Base58` + `TronKeys` add the `0x41`-prefix Base58Check (`T…`) address using JDK SHA-256 — no new dependency.
- `OfflineDepositPoolGenerator` emits TRON addresses (`--asset=USDT_TRC20`) under a **separate derivation
  label** so the TRON key set never reuses the Ethereum keys. `DepositAddressPoolService` import validates
  TRC-20 addresses by Base58Check prefix + checksum (ETH-family still by EIP-55). No schema change —
  `USDT_TRC20` was already a known asset.
- Verified against an independent Base58Check implementation (privkey 1 → `TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC`)
  and the real mainnet USDT-TRON contract address; full suite green (1006).

### 💰 Offline-generated deposit-address pool (watch-only, no keys on server)
- New deposit provider `offline-pool` (`app.payments.provider=offline-pool`): deposit addresses are generated
  **offline** (private keys + seed never touch the server) and only their **public addresses** are imported;
  the server hands them out one-per-user-per-asset as players request a deposit address. On a server breach
  there are no spendable keys to steal — only watch-only addresses and balances.
- `DepositAddressPoolEntry` + `DepositAddressPoolService`: allocation is **idempotent** per (user, asset) and
  **concurrency-safe** (the next free address is row-locked `SELECT … FOR UPDATE` while claimed, so two
  simultaneous registrations cannot grab the same one); an exhausted pool is rejected
  (`DepositAddressPoolExhaustedException`), not silently double-assigned.
- Admin API (`/v1/admin/wallet`, ADMIN role): `POST /deposit-pool/import` loads a batch of public addresses
  (idempotent — duplicates skipped; ETH-family addresses validated by EIP-55 checksum) and
  `GET /deposit-pool/status` reports free/assigned counts per asset for low-watermark monitoring.
- Offline generator `OfflineDepositPoolGenerator` (lives in **test sources** so it is excluded from the
  production jar — a key generator must never ship inside the online service): derives a batch of ETH-family
  keypairs from a single seed (reusing the pure-Java `EthKeys`; backup = the one seed + index range) and
  writes `private.json` (keep offline) + `addresses.json` (the POST-ready admin import body).
- Liquibase changeset `04-deposit-address-pool` (Hibernate-generated DDL via `sqlFile`, Postgres-only; H2
  tests regenerate from the entity). Verified: full suite green + a fresh-Postgres cluster boots with all
  **five** changesets applied and `ddl-auto=validate` passing on both nodes. Withdrawal/sweep stays out of
  scope (spend pooled funds via an offline signer); the provider's `broadcastWithdrawal` is intentionally
  unwired. Initial assets: ETH + USDT-ERC20 (shared Ethereum address); USDT-TRC20 is a documented follow-up.

### 💰 Auto-payout on tournament finish
- When a **real-money** tournament completes, `TournamentPayoutListener` (`@EventListener` on the existing
  synchronous `TournamentCompleted` domain event) credits every in-the-money finisher's crypto wallet with
  their share of the prize pool. The listener stays thin (flag check + delegate); the work lives in
  `TournamentWalletService.payoutOnCompletion`, which loads the tournament, computes each finisher's share
  via `Tournament.cryptoPrizeForPosition(position)` (prize pool = crypto buy-in × registrations, split by the
  tournament's payout-structure percentages), and credits each through the idempotent `payout`.
- **Idempotent and best-effort**: a re-fired completion event does not double-credit (the per-(tournament,
  user) key is stored in the unique `external_tx_id`), and a single finisher's failed credit is logged
  without aborting the others. **No-op** for play-money tournaments and when `app.payments.enabled` is off.
- Tournament gains `crypto_buy_in_amount numeric(38,18)` / `crypto_buy_in_asset varchar(32)` (Liquibase
  changeset `03-tournament-crypto-buyin`, Postgres-only; H2 tests regenerate from the entity). Verified:
  full suite green + a fresh-Postgres cluster boots with all four changesets applied and `ddl-auto=validate`
  passing on both nodes.

### 💰 Wallet ↔ tournament buy-in/payout bridge
- Real-money tournament entry: `TournamentWalletService.buyIn` debits the player's crypto `WalletAccount`
  and registers them **in one transaction** (if registration fails — full / already in / not open — the
  debit rolls back), and `payout` credits the wallet with a prize. Both are **idempotent per
  (tournament, user)** (the idempotency key is stored in the unique `external_tx_id` column), so a repeated
  buy-in neither double-charges nor double-registers.
- New ledger types `TOURNAMENT_BUYIN` (debit) / `TOURNAMENT_PAYOUT` (credit) with `WalletLedgerEntry`
  factories and `WalletService.chargeBuyIn`/`awardPayout` (flag-gated by `app.payments.enabled`). Liquibase
  changeset `02-wallet-tournament-ledger-types` widens the `wallet_ledger_entries.type` CHECK on Postgres
  (H2 tests regenerate it from the entity). `POST /v1/tournaments/{id}/buy-in` is the entry point.
- In-game chips stay play-money; the bridge only moves the real crypto balance (debit on entry, credit on
  payout). Verified: full suite green + a fresh-Postgres cluster boots with all three changesets applied and
  `ddl-auto=validate` passing.

### 💰 Crypto wallet — on-chain deposits + KYC-gated withdrawals (flag-gated skeleton)
- New `wallet` subsystem (default **off**, `app.payments.enabled`): a real-money crypto balance separate from
  in-game chips. Entities `WalletAccount` (authoritative balance per user+asset, optimistic-locked),
  `WalletLedgerEntry` (append-only audit; unique on-chain `external_tx_id`), `WithdrawalRequest`
  (state machine), `KycRecord` (per-user KYC status). New Liquibase changeset `01-wallet.xml` (generated
  from the entities) keeps Postgres in sync so `ddl-auto=validate` passes.
- **Deposits** are credited **idempotently by tx id** — a duplicate provider webhook (or redelivery) for the
  same transaction is a no-op (the same exactly-once discipline as the game's `commandId`).
- **Withdrawals** are gated on KYC (`VERIFIED`) when `app.payments.kyc-required-for-withdrawal`: the KYC
  check, balance debit, ledger entry and request are written in one transaction, then broadcast via the
  provider; insufficient balance and missing KYC are rejected (409 `KYC_REQUIRED` / 422 `INSUFFICIENT_FUNDS`).
- Provider-abstracted: `CryptoPaymentProvider` (allocate address / broadcast). `MockCryptoPaymentProvider`
  is the default (`app.payments.provider=mock`); a real HTTP-gateway adapter `GatewayCryptoPaymentProvider`
  (NOWPayments/CoinsPaid-style REST, conditional on `app.payments.provider=gateway`) is included as an
  integration skeleton with a **network mode** (`app.payments.network` + `gateway-base-url`) so the full flow
  can run on a value-less **testnet/sandbox** before mainnet — only the provider config changes, the wallet
  logic is identical. A **self-custody** option (`app.payments.provider=eth-self-custody`,
  `SelfCustodyEthPaymentProvider`) shows deposit addresses can be generated with **no external provider** at
  all: pure-Java `Keccak256` + `EthKeys` (secp256k1 → EIP-55 address), verified against canonical vectors,
  derive a deterministic per-user ETH address from a configured master key (demo-grade; production = HSM +
  watch-only BIP-32 xpub; withdrawal signing/broadcast intentionally not wired — needs a signer + node).
  Inbound provider callbacks
  (`/internal/wallet/deposit`, `/internal/wallet/kyc-callback`) are guarded by a constant-time shared-secret
  header, mirroring the cluster internal endpoint.
- **Withdrawal lifecycle completion**: a provider callback (`/internal/wallet/withdrawal-status`) finalizes a
  broadcast withdrawal — `CONFIRMED` (idempotent) or `FAILED`, where failure marks it FAILED and credits the
  debited amount back via a `WITHDRAWAL_REVERSAL` ledger entry (also idempotent; a confirmed payout cannot be
  reversed). No schema change (the reversal type already exists).
- API: `/v1/wallet/{balances,deposit-address,kyc,kyc/submit,withdrawals}`. Tests: `WalletServiceIT`
  (idempotent deposit, KYC-blocked withdrawal, post-KYC success, insufficient funds, confirm, fail+reversal)
  + `WalletServiceDisabledTest`.
- Verified end-to-end: the cluster boots on a fresh Postgres with the wallet changeset applied and validated.

### 🏗️ Liquibase changelog squashed to a clean baseline
- The incremental changelogs `01`–`14` had accumulated two overlapping schema lineages and could never run
  clean on a fresh Postgres (duplicate tables `04`/`05`, duplicate columns `06`/`08`, and — the blocker —
  `07-tournaments.xml` renaming/dropping a **pre-`04`** `tournaments` shape, e.g. `DROP COLUMN
  active_game_id` that `04` never creates).
- Replaced them with a single **baseline** (`db/changelog/00-baseline.xml` + `baseline/schema-postgres.sql`)
  generated from the current JPA entities (Hibernate `ddl-auto=create` → `pg_dump`), so a fresh migration
  matches the entities exactly and `ddl-auto=validate` passes. The old `01`–`14` files are archived under
  `db/changelog/archive/` (no longer included by the master changelog). Postgres-only (tests use H2 +
  Hibernate, with Liquibase disabled).
- Added a `UNIQUE (player_name)` constraint on `player_statistics` in the baseline — completing the
  get-or-create robustness fix (one stats row per player name).
- **Liquibase is re-enabled on the runnable cluster** (`docker-compose.cluster.yml`, `ddl-auto=validate`);
  verified end-to-end: both nodes boot on a fresh Postgres, the baseline applies once (`databasechangelog`
  has a single row), and validation passes.

### 🐛 Robustness under load (surfaced by the scaling benchmark)
- **Game creation no longer 500s under concurrency.** `PlayerStatisticsService.getOrCreateStats` was a
  non-atomic find-or-create on `player_name` (no unique constraint), so concurrent game starts that share a
  player/bot name inserted duplicate rows and the next single-result `findByPlayerName` threw
  `NonUniqueResultException` → 500. The by-name lookup is now `findFirstByPlayerName` (tolerant, LIMIT 1),
  and `createNewGame` treats `startSession` as a best-effort side effect (a stats failure is logged, never
  fails game creation). Verified: 80 concurrent `POST /v1/poker/game/start` went from 80/80 → 500 to
  80/80 → 201.
- **Cross-node forwarding no longer turns game-level conflicts into 500s.** `ClusterInternalController`
  now translates the owner's exceptions (`IllegalState` → 409, `NoSuchElement` → 404, `IllegalArgument`
  → 400) instead of letting them become a 500, and `ClusterActionForwarder` distinguishes a **4xx** from
  the owner (a real game rejection — surfaced to the client, the caller does NOT re-claim the table) from a
  **connect/timeout/5xx** (owner unreachable — caller may re-claim once). Previously a "not your turn" on
  the owner became a 500 that the forwarder mistook for an unreachable owner.
- Tests: `ClusterInternalControllerTest` (secret + 200/409/404/400 translation), a `ClusterActionForwarder`
  4xx-vs-5xx case, and a `PokerGameService` test that game creation survives a throwing `startSession`.

### 🚀 Ops — Runnable two-node cluster (Phase 5)
- `docker-compose.cluster.yml`: two backend nodes behind an nginx load balancer on a shared Postgres +
  Redis, with all Phase 5 flags on (ownership, cross-node routing, failover takeover, fencing, ws-cluster).
  Each node gets a distinct instance id (its hostname) and a peer-reachable `CLUSTER_NODE_BASE_URL`; node 1
  runs Liquibase first, node 2 waits for it. Entry point http://localhost:8080 (LB); nodes also on 8081/8082.
- `docker/nginx/cluster.conf`: `ip_hash` stickiness (keeps a client's WebSocket + REST on one node),
  SockJS/STOMP upgrade proxying, long read/send timeouts, and a 403 on `/api/internal/**` so the
  node-to-node endpoints are never reachable from clients via the LB.
- `docs/cluster.md`: guided run + failover verification (inspect leases / node registry / active-table set
  / fencing tokens in Redis; observe cross-node forwarding; kill a node and watch the survivor take over).
- Verified end-to-end on Docker: both nodes boot on shared Postgres + Redis, register in the node registry
  (`truholdem:cluster:node:*`), the nginx LB (host **8090**, to coexist with the single-node compose on 8080)
  serves `/api/actuator/health`, and `/api/internal/**` is blocked (403) at the LB.

### 🐛 Fixes
- `db/changelog/10-tournament-scale-phase1.xml` used the wrong XSI namespace
  (`http://www.w3.org/2003/XMLSchema-instance` instead of `…/2001/…`), so Liquibase could not resolve the
  schema and failed to parse the changelog (`Cannot find the declaration of element 'databaseChangeLog'`).
  Corrected to `2001`. (This file is now archived by the changelog squash above; the runnable cluster runs
  Liquibase on the squashed baseline.)

### 🏗️ Architecture — Engine migration Phase 5 (fencing tokens)
- Optional fencing tokens (`app.cluster.fencing-enabled`, default off, requires ownership + hot-state) to
  stop a stale former owner from clobbering state after a lease handoff (e.g. a long GC pause during which
  its lease expired and another node took over).
- Each lease acquisition carries a monotonic token in Redis (`truholdem:cluster:fence:{id}`), bumped only
  when ownership changes hands and kept across renewals. `TableOwnershipService` issues the token via an
  enhanced Lua acquire script and tracks the token this node holds per table.
- The authoritative Redis hot-state write (`RedisGameStateStore.save`) becomes an atomic Lua compare-and-set
  that rejects a write whose token is behind the table's current token, throwing `StaleOwnershipException`.
  The held-token map naturally scopes fencing to owned tables — cache-population writes (after a DB read of
  a table this node doesn't own) carry no token and take the plain path, so they are never rejected.
- Postgres remains independently guarded by the `Game` `@Version` optimistic lock; Redis is authoritative.
- Tests: `RedisGameStateStoreTest` (plain vs fenced write, accept vs reject); `TableOwnershipServiceTest`
  (token issuance / failure); `TableOwnershipRedisIT` adds real-Redis monotonicity (renewal keeps the
  token; a takeover after lease expiry strictly increments it). Default (fencing off) behaviour unchanged.

### 🏗️ Architecture — Engine migration Phase 5 (split-brain safety: fail-closed)
- Optional fail-closed ownership mode (`app.cluster.fail-closed`, default off). The Redis lease normally
  fails open — a node that can't reach Redis assumes it owns its tables, which keeps a single node playable
  but lets a partitioned node double-own a table in a real cluster. With fail-closed on,
  `TableOwnershipService.acquire`/`isOwner` return false when Redis is unreachable, so a partitioned node
  stops driving timers, claiming tables, and (with routing on) processing actions until Redis recovers.
- `acquire`/`isOwner` now distinguish single-node mode (ownership disabled → always owns) from cluster mode
  with Redis unreachable (fail-open vs fail-closed), instead of collapsing both to "owns everything".
- `TableOwnershipServiceTest` adds fail-closed coverage (Redis-missing and Redis-error refuse ownership;
  single-node mode is unaffected). Default fail-open behaviour is unchanged.

### 🏗️ Architecture — Engine migration Phase 5 (failover takeover)
- Automatic takeover of tables orphaned by a dead owner so a game no longer hangs waiting on a player the
  dead node was meant to time out (previously it recovered only lazily, on the next action for that table).
- Each node records active game tables in a Redis set (`truholdem:cluster:tables`, added by
  `TableOwnershipService.trackActiveTable` when a turn timer is armed, removed on game end);
  `ClusterFailoverService` scans the set ~twice per lease TTL and, for any table whose lease has expired
  (no current owner), re-acquires it and resumes the stalled turn timer. Finished/missing games are pruned.
- Gated by `app.cluster.takeover-enabled` (default off, requires `ownership-enabled`); inert otherwise.
- Takeover resumes whatever timer the dead owner was driving: the in-progress turn timer **and** the
  between-hands transition. `GameHandLifecycleService.resumePendingTransition` re-schedules a table
  orphaned in `HAND_COMPLETED`/`RESULT_DELAY` (so a game does not stall forever between hands either);
  each branch is state-guarded. Prune is keyed on the game being absent from shared state — not on
  `isFinished()`, which is also true between hands — so an active between-hands table is no longer
  mistakenly dropped.
- `MultiNodeClusterIT` adds a kill-node test: node-A is shut down and its lease expired, then node-B takes
  over the orphaned table and resumes its timer. `ClusterFailoverServiceTest` covers claim/resume,
  still-owned-skip, lost-race, and missing-game-prune; `GameHandLifecycleServiceTest` covers
  `resumePendingTransition` routing per state. (The narrow transient `NEXT_HAND` crash window is not
  resumed; it remains a documented follow-up.)
- Doc correction: WebSocket-origin actions were already routed cross-node (the WS handler calls the same
  `playerAct`); the earlier "WS-origin forwarding remaining" note was overly cautious.

### 🏗️ Architecture — Engine migration Phase 5 (cross-node command routing)
- Cross-node action routing so same-table multiplayer is correct across a cluster: `PokerGameService.playerAct`
  routes at the service layer — if this node can't acquire the table's lease it resolves the owner from a
  Redis node registry (`truholdem:cluster:node:{instanceId}` → peer base URL, written on startup and refreshed
  in the ownership heartbeat) and forwards the action to the owner over HTTP.
- New `ClusterActionForwarder` POSTs to the owner's `/internal/cluster/game/{id}/action` endpoint
  (`ClusterInternalController`), authenticated by a constant-time shared-secret header (`X-Cluster-Secret`).
  The owner applies the action on its own single-writer queue and persists to the authoritative shared
  hot-state; the originating node reloads and returns it. The `commandId` is carried through (exactly-once
  preserved), a non-routing `playerActLocal` path prevents forward loops, and one re-claim covers an owner
  that died mid-flight.
- Gated by `app.cluster.routing-enabled` (default off, requires `ownership-enabled`); routing-off behaviour
  is byte-for-byte unchanged. New config: `app.cluster.node-base-url` (this node's peer-reachable URL,
  must include the servlet context-path) and `app.cluster.shared-secret`.
- `MultiNodeClusterIT` upgraded to boot two web instances and verify an action sent to the non-owner
  node is forwarded over real HTTP to the owner and applied exactly once (owner retains the lease).
  `ClusterActionForwarderTest` covers the secret header, unknown-owner and owner-error paths.

### 🏗️ Architecture — Engine migration Phase 5 foundation (per-table ownership)
- New `TableOwnershipService`: a Redis-lease (`truholdem:owner:{uuid}` → node `instanceId`, atomic Lua
  acquire-if-free-or-mine + heartbeat renewal) giving each table/tournament at most one owner node.
- The turn-timeout, hand-lifecycle and tournament blind-level schedulers now acquire ownership before
  scheduling and re-check on fire, so on a multi-node cluster each timer fires on exactly one node
  (eliminating the per-JVM double-fire). Gated by `app.cluster.ownership-enabled` (default off);
  degrades to single-node behavior when disabled or Redis is unavailable.
- The lease is verified against real Redis (`TableOwnershipRedisIT`, Testcontainers): exclusive
  acquire, release handoff, and TTL-expiry failover.
- Multi-instance harness `MultiNodeClusterIT`: boots two full app instances against one shared
  Postgres + Redis (cluster mode on) and asserts cross-node ownership exclusivity — the base for
  verifying cross-node routing / failover. Cross-node command routing and live kill-node takeover remain.

### 🐛 Fixes
- Remove a duplicate `WebSocketEventListener` (`com.truholdem.listener` vs `com.truholdem.application.listener`):
  both were `@Component @ConditionalOnProperty(app.websocket.cluster.enabled)`, so enabling cluster mode
  crashed startup with a conflicting-bean-definition error. Surfaced by `MultiNodeClusterIT`.

### 🏗️ Architecture — Engine migration Phase 6 (cleanup & enforcement)
- Removed dead code: unused `GameUpdateType` values (`NEW_HAND`/`PLAYER_JOINED`/`PLAYER_LEFT`/
  `PHASE_CHANGE`/`GAME_ENDED`) and the never-called `broadcastPhaseChange`/`broadcastGameEnded` methods.
- `StatisticsController` and `AchievementController` now return DTOs (`PlayerStatisticsResponse`,
  `AchievementResponse`, `PlayerAchievementResponse`) instead of JPA entities; the tournament
  table-hand endpoint returns the sanitized projection. Wire JSON unchanged (contract-tested).
- New ArchUnit rule fails the build if any `@RestController` exposes `com.truholdem.model.*` in a
  return type (reflective generic-type scan), enforcing the controller↔persistence decoupling.

### 🏗️ Architecture — Engine migration Phase 4 (append-only event log + replay)
- New `game_event_log` table (Liquibase changeset 13): a synchronous `GameEventLogListener` persists
  every published domain event (JSON payload, global `seq_no` ordering, stamped `gameId`/`handNumber`).
  The writer runs in its own `REQUIRES_NEW` transaction and is best-effort, so an audit failure never
  blocks or rolls back a game action. Gated by `app.game.event-log-enabled` (default on; aggregate path).
- Replay-from-events read API on `HandHistoryController`: `GET /history/game/{id}/events` and
  `GET /history/game/{id}/hand/{n}/events` return the ordered domain-event stream for audit/replay.
- Reconnect/resume was already provided by the Redis `GameEventStore` layer and is left unchanged.

### 🏗️ Architecture — Engine migration Phase 3 (hand-history read-model)
- `HandHistoryController` now returns a dedicated `HandHistoryResponse` read DTO instead of the raw
  `HandHistory` JPA entity, decoupling the API from persistence. The wire JSON is byte-for-byte
  unchanged (no frontend change), pinned by a new `HandHistoryJsonContractTest`.

### 🏗️ Architecture — Engine migration Phase 3 (event-driven statistics)
- Domain events raised by the `PokerGame` aggregate are now **published to Spring** via
  `DomainEventPublisher` on the aggregate engine path (previously they were collected and dropped).
- `StatisticsEventListener` is implemented (and made synchronous) to derive player statistics from
  `PlayerActed`/`HandCompleted` events; the duplicate imperative `playerStatisticsService` calls were
  removed from the aggregate path. Wins are counted from `HandCompleted` only (no double counting).
- Gated by `app.game.engine=AGGREGATE`; the default legacy path keeps its imperative statistics.

### 🏗️ Architecture — Engine migration Phase 2 (single-writer per table)
- **`TableCommandDispatcher`**: every mutation for a table (`gameId`) is now serialized on a
  per-game command chain running over a shared bounded thread pool (no thread-per-table — scales
  to thousands of tables). Player actions, bot actions, turn timeouts and hand-lifecycle
  transitions all funnel through the same queue, eliminating the action-vs-timeout interleave and
  the `@Version` lost-update race on a single node.
- **`commandId` idempotency**: a per-table bounded TTL cache replays the recorded result/exception
  for a duplicate command, so a double-click or a duplicate WebSocket frame is applied exactly
  once. The id is supplied by the client (`X-Command-Id` header on REST, `commandId` field on the
  WS payload; the Angular `PokerService`/`WebSocketService` reuse the id across retries of the same
  action) and generated server-side when absent.
- **Feature flag** `app.game.single-writer-enabled` (default **off** → legacy lock-free path) gates
  the whole mechanism for fast rollback.

### 🧪 Testing
- `TableCommandDispatcherTest`: serialized no-lost-updates under concurrent load, parallelism across
  tables, exactly-once idempotency, original-type exception propagation, re-entrancy safety.
- `PokerGameService` routing test; frontend Jest specs for `X-Command-Id` send + retry reuse.

## [2.0.0] - 2024-12-16

### ✨ Features
- **Game Engine**: Complete Texas Hold'em implementation with all betting rounds
- **Tournament System**: Multi-table tournaments with Sit & Go and Scheduled formats
- **Bot AI**: Advanced AI with Monte Carlo simulations, opponent modeling, and GTO-inspired play
- **Hand Analysis**: Equity calculator, range builder, and EV analysis tools
- **Real-time WebSocket**: Cluster-ready WebSocket implementation with Redis pub/sub
- **Authentication**: JWT-based auth with refresh tokens and RBAC
- **Statistics**: Comprehensive player statistics and leaderboards
- **Hand History**: Full hand replay with action-by-action analysis

### 🏗️ Architecture
- **Backend**: Spring Boot 3.5 with Java 21, PostgreSQL, Redis
- **Frontend**: Angular 20 with NgRx ComponentStore, lazy loading, route guards
- **DDD**: Domain-Driven Design with aggregates, value objects, and domain events
- **Observability**: OpenTelemetry tracing, Prometheus metrics, Grafana dashboards

### 🧪 Testing
- 190+ unit and integration tests
- Cypress E2E tests with accessibility checks
- Architecture tests with ArchUnit
- 60%+ code coverage

### 🚀 DevOps
- Docker Compose for local development
- CI/CD pipeline with GitHub Actions
- Automatic semantic versioning and releases
- Security scanning with Trivy
- Dependabot for dependency updates

### 📚 Documentation
- Comprehensive README with setup instructions
- API documentation with Swagger/OpenAPI
- Architecture decision records
- Contributing guidelines

---
*Generated by [git-cliff](https://github.com/orhun/git-cliff)*
