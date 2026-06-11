# TODO — crypto wallet money-path

Remaining work on the crypto-wallet withdrawal/deposit path. Detailed context lives in
`FUTURE_IMPROVEMENTS.md`; this is the short actionable list. All coordinators are flag-gated and off by
default; signing always stays offline (signers live in test sources, never in the production jar).

## Done (verified on live nodes)
- ✅ ETH native + ERC-20 online coordinator — assemble → offline-sign → broadcast → reconcile, verified
  end-to-end on `geth --dev` (incl. a real deployed ERC-20).
- ✅ BTC P2WPKH online coordinator — UTXO select + fee/change → offline-sign (BIP-143/BIP-144) → broadcast →
  reconcile, verified on `bitcoind -regtest`.
- ✅ BROADCAST→CONFIRMED reconcile scheduler (idempotent, cluster-safe), flag-gated.
- ✅ Offline signers (ETH/ERC-20, BTC P2PKH/P2WPKH/Taproot, TRON) verified vs reference vectors.
- ✅ KYC key rotation + KMS provider + AV scan + re-encryption sweep.

## TODO — TRON coordinator (own slice)
- [ ] `TronRpcClient` — TronGrid / local `java-tron` HTTP API (`getnowblock` for the block ref,
      `triggersmartcontract`/`createtransaction`, `broadcasttransaction`, `gettransactioninfobyid`).
- [ ] `TronWithdrawalCoordinator` — assemble `raw_data` (TRX transfer + TRC-20 `transfer` via
      `triggersmartcontract`) from a recent block ref; broadcast the offline-signed tx; reconcile by tx info.
- [ ] Verify against a local `java-tron` private-net (Testcontainers). NOTE: heavy image / slow startup —
      budget for it; consider a nightly-only tag if it slows the suite.

## TODO — BTC coordinator hardening
- [ ] Recipient script types beyond P2WPKH: legacy P2PKH (`1…`) and Taproot P2TR (`bc1p…`) outputs.
- [ ] Multi-address / descriptor treasury (today a single `btc-from-address`); consolidate across addresses.
- [ ] `estimatesmartfee` with the flat sat/vByte rate as fallback (currently flat only).
- [ ] Replace-by-fee / fee bump for stuck txs (optional).

## TODO — ETH coordinator polish
- [ ] EIP-1559 (type-2) txs (`maxFeePerGas`/`maxPriorityFeePerGas`) — today legacy gas-price only.
- [ ] Surface `contractAddress` on `EthRpcClient.Receipt` (currently read via raw RPC only in tests).

## TODO — air-gapped signer ergonomics (QR transfer)
Replace the USB "sneakernet" between the online admin UI and the offline signer with QR codes (no USB =
no infected-stick vector; camera/screen only).
- [ ] Admin UI: render the exported unsigned intent (`…/{id}/eth-unsigned` / `…/btc-unsigned` / `…/unsigned`)
      as a **QR code** on `/admin/withdrawals` for the operator to scan on the offline machine.
- [ ] Offline signer: accept the intent via QR scan (camera) instead of CLI args; show `from`/`amount` for
      visual confirmation before signing (guards against a tampered intent).
- [ ] Read the signed raw-tx back into the UI via QR (camera) → `…/eth-broadcast` / `…/btc-broadcast`.
- [ ] **Chunking for large payloads**: BTC PSBTs / multi-input txs exceed a single QR — use an animated /
      multipart QR scheme (e.g. BBQr or BC-UR) with sequence + checksum, not a hand-rolled split.
- [ ] Keep USB/file transfer as a documented fallback.

## TODO — frontend
Backend is fully wired; frontend was admin-only. User wallet dashboard now started.
- [x] **User wallet dashboard** (`/wallet`) — balances + refresh, deposit address per asset (copy), withdrawal
      form, withdrawal history with status badges. Nav link gated by auth.
- [x] Deposit address as a **QR code** (`qrcode` lib → data-URL `<img>` on `/wallet`).
- [x] KYC as a guided flow on `/wallet` — the KYC component is embedded (`[embedded]="true"`) as a
      verification card showing status → upload → pending → verified inline; standalone `/kyc` kept.
- [x] Admin: **status filter** dropdown on `/admin/withdrawals` (`?status=`).
- [x] Admin: **chain-specific signing workflow** UI on `/admin/withdrawals` — Assemble ETH/BTC (coordinator)
      → paste signed raw → Broadcast signed → Reconcile / Status, dispatched by asset network.
- [x] Admin: **deposit-pool dashboard** (`/admin/pool`) — free/assigned per asset with low-watermark flag +
      batch import (paste offline `addresses.json`).
- [x] Admin: **KYC re-encrypt** button on `/admin/kyc` (`POST /kyc/re-encrypt`, confirm + result line).
- [x] UX polish: withdrawal status polling (while any in-progress), KYC upload progress bar, success/error
      toasts via the global notification service. (Remaining nicety: asset-selector friendly labels.)

## TODO — cash games (real-money ring tables) [NEW EPIC]
The platform has play-money hands + tournaments (with a wallet bridge) but **no cash/ring tables**: no
table-config, no sit-down/buy-in, no stand-up/cash-out, no wallet↔table bridge, no rake, no lobby. Slices:
- [x] **1. Table config entity** — `CashTable` (stakes SB/BB, min/max buy-in, max seats, asset, rake bps + cap,
      active) + `CashTableRepository` + Liquibase changeset 10. Verified H2 + fresh Postgres.
- [x] **2. Seat/session model** — `CashSeat` (table, user, stack + buy-in total, seatNo, `CashSeatStatus`
      ACTIVE/SITTING_OUT/LEAVING/LEFT, joined/left, `@Version`) + `CashSeatRepository` (active seats, live seat
      per player, seat-number occupancy, live count). Liquibase changeset 22 (`cash_seats` + index). Verified by
      `CashSeatRepositoryIT` + fresh Postgres `validate`. (chip↔asset scale deferred to the engine-wiring slice.)
- [x] **3. Sit-down (buy-in)** — `CashGameWalletService.buyIn`: validate active table + `[min,max]` buy-in +
      lowest-free seat + one live seat per player, create `CashSeat`, debit `WalletAccount` (new `CASH_BUYIN`
      ledger type via `WalletService.chargeCashBuyIn`, idempotent on the seat id). Liquibase changeset 23 widens
      the ledger-type CHECK. Verified by `CashGameWalletServiceIT` (7 cases) + fresh-Postgres CHECK insert proof.
      Concurrency-safe seat assignment (DB constraint / per-table writer) deferred to the engine/API slices.
- [x] **4. Stand-up (cash-out)** — `CashGameWalletService.cashOut`: credit the seat's remaining stack back
      (new `CASH_CASHOUT` ledger type, idempotent on the seat id; busted/zero stack = free seat, no credit) and
      mark `CashSeat` LEFT; `requestLeave` marks LEAVING for a mid-hand stand-up. Liquibase changeset 24 widens
      the ledger-type CHECK. Verified by `CashGameWalletServiceIT` (10 cases) + fresh-Postgres CHECK insert proof.
      Deferred cash-out *after the hand* on a mid-hand leave is wired in slice 6 (engine).
- [x] **5. Rake** — `CashRakeService.computeRake` (pure: bps + cap + round-down + no-flop-no-drop) +
      `collectRake` (records `CashRakeEntry` house revenue, idempotent on the settling hand id) +
      `houseRevenue`. Liquibase changeset 25 (`cash_rake_entries`, unique idempotency key). Verified by
      `CashRakeServiceIT` + fresh-Postgres validate. Deducting the rake from the awarded pot is the engine slice.
- [ ] **6. Engine wiring** (Option A: reuse the pure `domain.aggregate.PokerGame` kernel for cash; tournaments
      stay on the default `legacy` engine — zero blast radius). Sub-slices:
  - [x] **6a. money↔chip scale + kernel decision** — `CashChipScale` (per-table chip unit = 10^-d from the
        blinds; `toChips`/`toMoney`/`dust`, int-overflow guarded). Verified by `CashChipScaleTest` + the
        aggregate kernel gate (PokerGame{,Rules,Showdown,Betting} tests). No tournament code touched.
  - [x] **6b. cash driver** — `CashGameService.startHand` (map ACTIVE seats → engine chips via `CashChipScale`,
        build the aggregate `PokerGame` with the table's fixed blinds, deal) + `settleHand` (rake each
        `PotAwarded` pot in money off the winner — no-flop-no-drop, house revenue via `CashRakeService` — write
        final stacks back to seats, deferred cash-out for LEAVING seats). Verified by `CashGameServiceIT` (fold
        no-rake, showdown raked, leaving cashed out). Kernel un-mutated; tournaments untouched.
  - [x] **6c. live-hand persistence** — `CashGameService.openHand` persists the dealt hand as a `games` row via
        the aggregate↔JPA `PokerGameMapper` + links `cash_tables.current_game_id` (changeset 26); `act` reloads
        from the DB, applies one action, and persists-or-settles+frees the table; `peekHand` reconstitutes.
        Storing via the JPA `Game` entity (not a JSON snapshot) keeps hole cards (`@JsonIgnore` but
        `@ElementCollection`). Verified by `CashHandPersistenceIT` (hole cards survive; a hand driven entirely
        through `act()` reloading from the DB reaches showdown, rakes, frees the table). Remaining: cluster
        ownership of an always-on table (no-op single-node today).
- [x] **7. REST API** — flag-gated (`app.cash.enabled`) cash surface: player `/v1/cash/tables`
      (lobby, state, sit, leave, deal, act) + admin `/v1/admin/cash/tables` (create). DTOs + `CashGameService`
      orchestration (`createTable`/`listActiveTables`/`seatsOf`/`sit`/`actAsUser`/`leaveTable`). Verified by
      `CashTableControllerIT` (full HTTP flow) + `CashTableControllerDisabledIT` (404 when off).
- [x] **8. Lobby + table UI** — Angular `cash` feature: lobby (`/cash`, list + sit with buy-in) + table
      (`/cash/:id`, seats, current hand with your own hole cards, deal/fold/check/call/raise/leave) + 💵 Cash
      nav link. `CashService`/`cash.models.ts`/`CashLobbyComponent`/`CashTableComponent`, lazy routes behind the
      auth guard. eslint + `ng build` green.
- [x] **9. Verify** — `CashGameEndToEndIT`: deposit → sit → deal → play a contested showdown over the `act`
      endpoints → cash out, asserting money is conserved (final wallets 99.99 + 0.01 house rake = 100 deposited).
      Full suite green. **Cash-games epic complete (slices 1–9).** Remaining follow-up: cluster ownership of an
      always-on table (no-op single-node today).
Open design questions: chip↔money scale; rake model (no-flop-no-drop?); mid-hand leave; bot seating; per-table
single-writer under the existing cluster ownership.

## TODO — buy-up pyramid tournament [NEW EPIC]
A pyramid variant (real-money) that **starts when full**; before start, a player can **buy a guaranteed seat at
a higher level**, closing the fight of the whole sub-pyramid below it. Fixed bracket tree (1000→100→10→1 for
seats=10): each level-L seat is fed by one level-(L-1) table. Rules (clarified):
- Buy only **before start**; seat at level L is buyable **only if its sub-pyramid is empty** (no registered
  players below). Buyable level-L seats = clean feeder tables at level L-1 (e.g. 11 occupied L1 seats dirty 2
  tables → 98 of 100 L2 seats buyable).
- **Price = sub-tree buy-ins** = `seatsPerTable^(L-1) × buyIn` (L2 = 10×, L3 = 100×).
- Cap: ≤ 10 buy-outs.
Slices:
- [x] **1. Bracket + pricing core** — `PyramidBracket` (levels, table counts, sub-tree seats, buy-out price,
      buyable seats) + unit tests vs the 1000/10 example.
- [x] **2. Model + flag** — `pyramidBuyUpEnabled` flag on Tournament + `PyramidBuyout` entity/repository
      (tournament, buyer, level, seat index, price, asset) + Liquibase changeset 14 (unique: one per player,
      one per seat). Verified H2 + fresh Postgres.
- [x] **3. Buy-seat service + charge** — `PyramidBuyoutService` (availableTickets + buySeat): validate empty
      sub-tree (above the registration frontier) + no overlap + cap + range + registered + REGISTERING; the
      price **replaces** the buy-in (refund base + charge sub-tree price). Verified by H2 IT. (REST endpoint
      moves to the UI slice.)
- [x] **4. Fixed-bracket seating plan** — `PyramidSeatingPlanner`: floor players fill level-1 in order
      skipping closed (bought) sub-trees; buyers placed at their level; over-capacity rejected. Pure +
      unit-tested. (Wiring this plan into the live start/advance engine = slice 5.)
- [x] **5a. Seating at start wired** — `completeStart` branches on the flag: seats the floor per the planner,
      tags level-1 tables `bracketLevel=1`, buyers stay unseated; existing pyramid/other types unchanged
      (changeset 15). Verified H2 + fresh Postgres + regression (PyramidAdvanceRoundIT).
- [x] **5b. Engine advancement** — `advanceBuyUpToNextRound` advances through the fixed bracket: closes old
      tables, seats survivors + the buyers whose level == the new round (higher buyers stay deferred), skips
      empty levels, tags tables `bracketLevel`, marks the final table when none deferred. Flag-gated (normal
      pyramid unchanged); verified by `PyramidBuyUpRunIT` (run-to-champion) + regression `PyramidAdvanceRoundIT`.
- [x] **6. Refund/edge** — `cancelAndRefund` is buy-up-aware: a buyer is refunded the seat price they paid
      (distinct `tbuyup-refund:` key), a plain registrant the flat buy-in. Covers "never fills" (under-filled
      buy-up pyramids cancel through the same path). Verified by a new `PyramidBuyoutServiceIT` scenario.
- [x] **7. UI + verify** — player REST (`PyramidBuyoutController`: `GET …/pyramid/tickets`,
      `POST …/pyramid/buy-seat`) + a lobby "tickets" panel (`PyramidBuyUpPanelComponent`) showing each buyable
      seat's price with a Buy button (gated on `pyramidBuyUpEnabled` + REGISTERING). Verified end-to-end by
      `PyramidBuyoutControllerIT` (HTTP→wallet→DB). **Epic complete.**
Decided: buyer is a **registered player**; **max 1 buy-out per player** (DB-enforced) + total cap 10; the
buy-out price **replaces** the buy-in (= sum of the sub-tree's level-1 buy-ins); the player UI shows a
"ticket" per buyable seat at each level with its computed price. Real-money only (needs `cryptoBuyInAmount`).

## TODO — admin reschedule + notifications [NEW]
- [x] **Postpone under-filled tournament + e-mail registrants** — `POST /admin/tournaments/{id}/reschedule`
      → `TournamentService.rescheduleIfUnderfilled` (REGISTERING + future-time + not-yet-full guards) +
      `TournamentNotificationService` e-mail via the existing flag-gated `EmailService`. Verified by
      `TournamentRescheduleIT`.
- [ ] **SMS channel** — `users` has no phone column and no SMS gateway is wired; add a `phone` column +
      a provider adapter (could reuse the existing `RestClient`, no new dep) behind a flag, then extend
      `TournamentNotificationService` to fan out to SMS as well as e-mail.
- [ ] **Admin UI button** — add a "Postpone / reschedule" action (date-time picker) to the admin tournament
      detail panel, calling the new endpoint and surfacing the notified-count toast.

## TODO — federated (sharded) pyramid tournament [NEW EPIC]
A very-large pyramid split into shards that fill/run in waves, then a final among the shard winners. Canonical:
1,000,000 → 100 shards × (10,000 → 1 winner) → final 100 → 10 → 1. Decided: shards fill in **waves** by
capacity; each shard pinned to a **physical node-group**; final waits on a **strict barrier of all 100
winners**, then an **admin sets the start time + e-mails finalists**; registration deadline can be
**indefinite**; **play-money** first (real money later). Reuses `PyramidBracket` + `PyramidTournamentService`
(each shard / the final is an ordinary PYRAMID tournament run to a champion).
- [x] **1. Model + decomposition** — `FederatedPyramidPlan` (pure: shardCount, finalists, shard/final
      brackets), `PyramidFederation` + `PyramidFederationShard` entities + status enums + repositories,
      `federated-pyramid-enabled` flag, Liquibase 16 (two tables). Verified (plan unit + repo IT + fresh
      Postgres `validate`).
- [x] **2. Registration + wave fill** — `FederatedPyramidService`: `createFederation` (shard skeleton +
      node-group pinning), `register` (fill-order assignment, full shard → READY, idempotent, full-rejection),
      `promoteShards` (materialize READY shards into running child pyramids up to the `federated-max-concurrent-shards`
      cap). Changeset 17 (`pyramid_federation_registrations`). Verified by `FederatedPyramidServiceIT`.
- [x] **3. Shard run → winner capture** — `runShardToWinner` (runToCompletion, no big tx) + `recordShardWinner`
      (self-proxy tx: COMPLETED + `winner_player_id`, promote next wave, flip to AWAITING_FINAL when all done)
      + `drainShards` driver. Verified by `FederatedPyramidServiceIT` (all shards → winners → AWAITING_FINAL).
- [x] **4. Finalists barrier + scheduled final** — `scheduleFinal` (AWAITING_FINAL → FINAL_SCHEDULED, admin
      sets time, e-mails finalists via `notifyFederationFinalScheduled` + `EmailService` template) + `startFinal`
      (create + seed the final pyramid from the shard winners, start, → FINAL_RUNNING). Verified by
      `FederatedPyramidFinalIT` (e-mail to real users, end-to-end seeding, guards).
- [x] **5. Final run → grand champion** — `runFinalToChampion` (runToCompletion, no big tx) + `recordChampion`
      (self-proxy tx, idempotent): set `champion_player_id`, federation COMPLETED. Closes the engine lifecycle;
      verified by an end-to-end `FederatedPyramidFinalIT` scenario.
- [x] **6a. REST (admin + player)** — `AdminPyramidFederationController` (create/detail/promote/schedule-final/
      start-final/run-final/drain) + `PyramidFederationController` (player register + status), flag-gated, DTOs
      `CreateFederationRequest`/`FederationDetailResponse`/`FederationRegistrationResponse`. Verified by
      `FederatedPyramidControllerIT`.
- [x] **6b. Admin UI** — `/admin/federations` page (nav link 🔗 Pyramids): create form + live lifecycle panel
      (status, per-shard-status chips, promote/run-shards/schedule-final/start-final/run-final/refresh).
      `AdminFederationService` + models. eslint + `ng build` green.
- [x] **6c. Player UI** — `FederationViewComponent` at `/federations/:id` (auth-guarded): status + shard
      progress + Register button + "your shard". Player `FederationService` + models. Also fixed the 6b admin
      route (moved to a top-level `/admin/federations` so the nav link resolves). eslint + `ng build` green.
- [x] **7. Real money** — federation buy-in (`crypto_buy_in_amount`/`asset`, changeset 18); `register` charges
      via `WalletService.chargeBuyIn` (idempotent, rolls back if under-funded); `distributePrizes` on completion
      pays the net pool with TWO combined logics summing to 100%: a flat per-shard-winner qualifier
      (`federated-shard-winner-ppm`, default 1 ppm = 0.0001% each) + the final-table places
      (`federated-final-table-place-bps` 2nd/3rd… + `…-rest-bps`, default 2%/1% + 1% split), with the grand
      champion taking the remainder (absorbs rounding). Organisation fee (≤20%, `feeBasisPoints`) is withheld off
      the top first. The prize config is snapshotted onto the federation at creation and **admin-editable until
      payout** (`POST …/prize-config` + a prize-config panel on `/admin/federations`; changeset 28). Verified by
      `FederatedPyramidPrizeIT` (both logics + per-federation override + pool conservation) + `FederatedPrizeSplitTest`.
- [x] **8. Cluster/load verify** — `registerBotsBatch` + admin `register-bots` endpoint (enables wave load);
      node-group pinning balance verified (`FederatedPyramidNodeGroupIT`: 12 shards → 4/4/4 over 3 groups) +
      batch-fill tests; documented manual wave load on the scale cluster (`load/k6/README.md`). **Epic complete.**
      Follow-up: engine-level table affinity to a shard's node-group (today: balanced metadata + LB/ops hint;
      shards already distribute via lease ownership).

## TODO — buy-up federated pyramid [NEW EPIC]
A federated pyramid variant where players can buy guaranteed higher-level seats. Decided: buy-up both in the
shard and in the final; mechanics first, money later.
- [x] **1. Shard-level buy-up** — `buy_up_enabled` flag (changeset 19) + `BUYUP_OPEN` shard status;
      `openShardForBuyUp` closes a shard's registration under-filled (so upper seats are buyable), materializes
      a real-money buy-up child pyramid (charged at shard seating via the bridge), reuses `PyramidBuyoutService`;
      `closeBuyUpAndStart` starts it. Verified by `FederatedBuyUpShardIT`.
- [x] **2. Final-level buy-up** — `buyFinalSeat` (close an empty shard for `shardSize × buyIn`, become its
      finalist) + `availableFinalSeats`; `PyramidFederationFinalBuyout` (changeset 20); barrier counts
      `completed + final-buyouts`; final seeded from winners + buyers. Verified by `FederatedFinalBuyoutIT`.
- [x] **3. Admin prize distribution** — `distributeFederationPrizes(fedId)` + admin endpoint
      `POST …/distribute`: pool = **expected buy-ins** (`shardCount × shardSize × buyIn`, guaranteed), paid out
      as a flat per-shard-winner qualifier (`federated-shard-winner-ppm`) + the rest to the champion (shares one
      `payPool` core with the plain auto-payout). Idempotent. Verified by `FederatedPyramidPayoutIT`.
- [x] **4a. REST** — admin `open-buyup` / `close-buyup` (+ existing `distribute`); player `final-seats` (GET) +
      `final-seats/{i}/buy`. DTOs `FinalSeatResponse`/`FinalSeatPurchaseResponse`. Verified by
      `FederatedBuyUpControllerIT`.
- [x] **4b. UI** — admin buy-up controls (create checkbox + buy-in, open/close window, distribute) + player
      final-seat tickets section (`AdminFederationService` / `FederationService` extended). eslint + `ng build`
      green. **Epic complete.**

## TODO — scale / load
- [x] **WS capacity scenario (cluster × N WS clients)** — `load/k6/websocket-cluster.js` + `run-ws-cluster.sh`
      / `.ps1`: STOMP-over-WS fleet through the round-robin LB, per-node `websocket_sessions_local` + heap
      report. Script compiles (`k6 inspect`), runner `bash -n` clean; `docker/nginx/scale.conf` tuned (32768
      worker_connections, long WS timeouts).
- [ ] **Actual sustained 10k run** — point the scenario at a sized cluster (≥4–8 nodes) + PgBouncer + a
      multi-host k6 generator (single host is fd/port-bound). Capture per-node session split, heap, broadcast
      latency, connect-success at 10k. This is the ops exercise; the instrument is ready.
- [ ] **Batch chip-sync** — `TournamentChipSyncService.syncAfterHand` saves per-player in a loop; switch to
      `saveAll` (batch) to cut DB round-trips under large-field load.

## TODO — cross-cutting / production-readiness
- [x] Live AWS-KMS-backed `KycKeyProvider` is done; **hot-float / treasury balance monitor + alert** —
      `WalletMetrics` now publishes per-asset `truholdem_wallet_liabilities` (Σ of all wallet balances),
      `truholdem_wallet_withdrawals_in_flight_amount`, and the operator-declared
      `truholdem_wallet_reserve_float` (`app.payments.reserve-float.<ASSET>`); Prometheus alerts
      `WalletReserveFloatLow` (>85%) and `WalletInsolvencyRisk` (liabilities+in-flight > float) fire as they
      approach/exceed the custodied float. Follow-up: auto-read the on-chain treasury balance (watch-only
      providers) instead of the operator-declared float, so the reference is observed rather than configured.
- [ ] On-chain/AML screening (Chainalysis/Elliptic) on the deposit + withdrawal paths.
- [ ] Per-coordinator metrics (broadcast latency, reconcile lag, pending-confirmations gauge).
- [ ] Decide on 4-eyes (2-of-N moderator approval) — intentionally NOT done; revisit if compliance requires.
- [ ] **House-revenue admin page (frontend)** — surface `GET /api/v1/admin/wallet/house-revenue`
      (`HouseRevenueResponse`: per-asset tournament-fee + cash-rake + total) in the admin UI, e.g. on the
      wallet/admin dashboard. Backend + metric (`truholdem_wallet_house_revenue{asset,source}`) already exist;
      this is the read-only view for operators.
- [ ] **Operator/treasury withdrawal path for house revenue** — today the withheld fee/rake accrues in the
      custodial treasury and is only *recorded* (`tournament_fee_entries` / `cash_rake_entries`); there is no
      in-app way to move it out. Add an operator withdrawal flow (or a treasury sweep) so house revenue can be
      paid out to the operator's address, reconciled against the recorded totals. Must NOT credit a user
      `WalletAccount` (would inflate the solvency-monitor liabilities) — model it as a treasury/operator account
      kept out of the `liabilities` sum, with its own audit trail.

## TODO — deposit→treasury sweep / consolidation [NEW EPIC]
Deposits land on per-user **watch-only** pool addresses (`DepositAddressPoolService`), but withdrawals are
funded from a **single** treasury `from-address` per chain (`app.payments.eth-from-address` /
`btc-from-address`). Nothing moves funds between them, so: liquidity strands on hundreds/thousands of deposit
addresses; the treasury can run dry while the platform is solvent in aggregate; and the solvency monitor
compares against an operator-**declared** float, not the observed on-chain balance. This epic adds an
offline-signed **sweep** that consolidates deposit balances into the treasury, reusing the withdrawal
coordinators' assemble→offline-sign→broadcast→reconcile pipeline.
Decided:
- **Off by default** behind `app.payments.sweep.enabled`; signing **always offline** (sweep = "withdraw to
  self"); signers stay in test sources, never in the production jar.
- **Does NOT touch `WalletAccount` / user ledger** — it is an internal custody move; its own `SweepBatch` audit
  journal, kept **out** of the solvency `liabilities` sum.
- **Idempotent + cluster-safe** (lease ownership, mirroring `WithdrawalReconcileScheduler`).
- **BTC-first** (cleanest to prove on regtest); ETH/ERC-20 + TRON follow.
- Sweep fee is an **operational expense**, NOT house revenue.
Slices:
- [x] **1. BTC consolidation (UTXO, MVP)** — `BtcSweepCoordinator` (`planSweep` selects ASSIGNED-pool-address
      UTXOs ≥ `sweep.min-amount-per-asset`, ≤ `sweep.batch-max-inputs`; N inputs → 1 treasury output + fee,
      BIP-143/144; `broadcast`; `reconcile`). The offline signer signs **each input with its own derivation key**
      (per-input `derivationIndex` carried in `BtcSweepUnsignedDto`). `SweepBatch` entity/repo + Liquibase
      changeset 29 + `Payments.Sweep` config (flag-gated, off by default). Verified end-to-end on
      `bitcoind -regtest` by `BtcSweepCoordinatorIT` (fund 3 pool addresses → sweep → one consolidated UTXO on
      the treasury; 1 passed).
- [ ] **2. Reconcile + scheduler** — extend/mirror `WithdrawalReconcileScheduler` (BROADCAST→CONFIRMED for sweep
      batches, lease-owned, idempotent retry) + a `@Scheduled` batch planner (threshold / cron, flag-gated).
- [ ] **3. ETH/ERC-20 sweep (gas-funded)** — `EthSweepCoordinator`, per-address (account model): native = one
      `transfer`; ERC-20 = treasury funds gas to the deposit address, then `transfer` the token (2–3 tx / 2 sigs
      per address). Verify on `geth --dev` + a deployed ERC-20. Optional later: deposit/forwarder contracts
      instead of gas-funding.
- [ ] **4. TRON sweep** — after the TRON withdrawal coordinator lands (account model; cheap).
- [ ] **5. Custody journal + reconciliation + metrics** — read view (`Σ deposited − Σ swept − Σ on-deposit ≈
      on-chain treasury`) closing the solvency follow-up (observed balance vs declared float); metrics
      `truholdem_wallet_swept_total{asset}` / `_sweep_fee_total` / pending-batches gauge.
- [ ] **6. Admin UI** — on `/admin/pool` (or `/admin/wallet`): Plan sweep (preview sums + fee) → assemble →
      paste signed → broadcast → reconcile, mirroring the `/admin/withdrawals` signing workflow.

## TODO — USDT-Solana (new chain) [NEW EPIC]
The wallet has BTC (UTXO) + ETH/ERC-20 (account) coordinators; USDT lives on TRC20/ERC20 only. Solana is the
cheapest *liquid* USDT rail (~$0.0008/transfer vs BTC consolidation ≈ $408k for a 1M-deposit field — see the
fee analysis), so add USDT-Solana as a first-class asset with its own coordinator, mirroring the **ETH path**
(account model → one signature per withdrawal). Solana is its own cryptography — **ed25519 keys + base58
addresses** (NOT secp256k1) — so it does not reuse the BTC/ETH crypto, but it does reuse the
assemble→offline-sign→broadcast→reconcile shape, flag-gating, deposit-pool, and withdrawal state machine.
Decided:
- **Off by default** behind `app.payments.sol-rpc-enabled`; signing always offline (ed25519 signer in test
  sources, never in the production jar).
- **Account model** like ETH → 1 signature per withdrawal; SPL-token (USDT mint) transfers.
- **Recent-blockhash expiry is the air-gap wrinkle**: a Solana tx is signed against a recent blockhash valid only
  ~150 slots (~60–90s), so a slow offline round-trip expires it. Decide (a) a fast online-assemble→offline-sign→
  broadcast window, or (b) **durable nonce accounts** (on-chain nonce that doesn't expire) for a relaxed air-gap
  round-trip — lean to durable nonce for parity with the USB/QR offline flow.
- New asset `CryptoAsset.USDT_SOL("USDT","SPL",6)`.
Slices:
- [x] **1. Keys + base58 + ATA derivation** — `SolKeys` (ed25519 via JDK: deterministic keypair from a 32-byte
      seed, raw pubkey, base58 address, sign/verify, validation) + reused `Base58` (fixed an all-zero/leading-zero
      decode bug) + `SolAta` (ed25519 on-curve check + `findProgramAddress` + ATA from `[owner, TOKEN_PROGRAM,
      mint]`). Verified vs RFC 8032 §7.1 vectors (`SolKeysTest`) + on-curve/structure (`SolAtaTest`); no Tron/Btc
      regression. NOTE: the exact (owner,mint)→ATA value is cross-checked on `solana-test-validator` in slice 5.
- [x] **2. RPC client** — `SolanaRpcClient` (JSON-RPC 2.0 via RestClient): `getLatestBlockhash`,
      `getTokenAccountBalance`, `getTokenAccountsByOwner`, `sendTransaction`, `getSignatureStatus`. Flag-gated on
      `app.payments.sol-rpc-enabled` (+ `sol-rpc-url` config). Pure request-builder + response parsers unit-tested
      vs sample Solana payloads (`SolanaRpcClientTest`); live calls covered by slice 5.
- [x] **3. Withdrawal coordinator** — pure-Java Solana tx serialization (`SolShortVec` compact-u16, `SolMessage`
      legacy-message compile with account de-dup/ordering, `SolInstructions` SPL `transfer` + ATA
      `CreateIdempotent`, `SolTransaction` base64) + `SolWithdrawalCoordinator` (`buildUnsigned` SPL transfer
      treasury-ATA → recipient-ATA, creates the recipient ATA if missing, against a `finalized` blockhash;
      `broadcast`; `isConfirmed`). Account model → 1 ed25519 signature, signed offline. **Verified end-to-end on
      `solana-test-validator`** (`SolWithdrawalCoordinatorIT`: provision USDT mint + treasury ATA → assemble →
      offline-sign → broadcast → confirmed → recipient ATA balance moves) + serialization unit tests
      (`SolShortVecTest`/`SolMessageTest`). This also lands slice 5's on-validator proof for the withdrawal path.
- [x] **4. Deposit + ATA ingestion** — `CryptoAsset.USDT_SOL("USDT","SPL",6)`; `DepositAddressPoolService`
      validates SPL deposit addresses (base58 32-byte) on import; the offline `OfflineDepositPoolGenerator` emits
      Solana deposit addresses = each owner's USDT ATA. `DepositIngestionService` is network-agnostic, so a
      detected Solana deposit resolves by address → user → credit. Verified by `DepositSolanaIngestionIT` (import
      ATAs → allocate → confirmed deposit credits the owner; malformed address rejected) + deposit regression
      green. Remaining (ops/follow-up): the deposit ATA must exist on-chain to receive SPL — pre-create deposit
      ATAs (funded, offline-signed, ~0.002 SOL rent each), analogous to the sweep epic; decide payer.
- [x] **5. Wiring + verify** — `SolWithdrawalCoordinator` now drives the standard withdrawal state machine
      (`buildUnsigned(withdrawalId)` / `broadcast` → `recordBroadcast` / `reconcile` → `confirmWithdrawal`, via
      `WalletService`); admin `sol-unsigned` / `sol-broadcast` / `sol-reconcile` endpoints (mirror `btc-*`);
      `WithdrawalReconcileScheduler` `USDT_SOL` case; flag-off → coordinator bean absent
      (`SolWithdrawalCoordinatorDisabledTest`). `SolWithdrawalCoordinatorIT` now runs the **full flow** end-to-end
      on `solana-test-validator`: credit → request → approve → assemble → offline ed25519-sign → broadcast →
      reconcile to CONFIRMED, recipient ATA balance moves (250 USDT). `WithdrawalReconcileSchedulerTest` covers
      the new dispatch. **Epic complete (slices 1–5).**
Open: a Solana sweep (deposit ATAs → treasury) is a later slice under the sweep epic; durable-nonce vs
fast-window for air-gap; whether to also add USDC alongside USDT; pre-create deposit ATAs (from slice 4).

## TODO — isolated-custody federated pyramid (on-chain per-player wallets, Solana-first) [NEW EPIC]
A real-money federated pyramid variant where each player's buy-in is paid **on-chain into a dedicated,
per-tournament, per-player wallet** (offline-generated Solana ed25519 keypair; its USDT ATA is the deposit
target). New tournament ⇒ new wallets; keys are generated offline (server watch-only). Decided: **isolated
custody** (funds stay on the per-player wallets; the prize is consolidated from those wallets directly to the
winners — no central treasury); **full federated scale** (up to 1M wallets/tournament — cost accepted);
**registration gated on a confirmed on-chain deposit**; **Solana-first** (reuses the USDT-Solana rails). Flag-gated
NEW variant — existing federated pyramids (off-chain `chargeBuyIn`) untouched.
- [x] **1. Foundation** — `isolatedWalletsEnabled` federation flag (+ `app.tournament.federated-isolated-wallets-enabled`,
      USDT_SOL-only); `FederationPlayerWallet` entity/repo/pool (`FederationPlayerWalletService`: import/assign/
      confirmFunding) + Liquibase 30; `OfflineDepositPoolGenerator.generateFederationWallets` (offline ed25519 owner →
      USDT ATA per `fedwallet:<fedId>/<i>`); `FederatedPyramidService.registerIsolated` (assign a dedicated wallet,
      unseated/unconfirmed) + `confirmDeposit` (FUNDED + seat into a shard by fill order) + `reconcileDeposits` (poll
      `SolanaRpcClient.getTokenAccountBalance`). Admin `import-wallets`/`reconcile-deposits` + player register dispatch
      (returns the dedicated deposit ATA). Verified on H2 (`FederationIsolatedWalletIT`) **and end-to-end on
      `solana-test-validator`** (`FederationIsolatedWalletValidatorIT`: register → on-chain USDT deposit → reconcile
      seats the player). Existing federated tests green.
- [x] **2. Fill/lifecycle + no-show** — confirmed-only fill is already guaranteed (unconfirmed regs keep
      `shardIndex = -1`, excluded from `findByFederationIdAndShardIndex`; `startShard` seeds via `registerPlayer`
      with no re-charge since the buy-in is paid on-chain). Added **no-show release**:
      `FederatedPyramidService.releaseNoShows` frees assigned-but-unfunded wallets past
      `app.tournament.federated-isolated-deposit-window-minutes` back to the FREE pool and drops their pending
      registrations (so the wallet is re-usable); funded wallets untouched. Admin `POST .../release-no-shows`.
      Verified by `FederationIsolatedWalletIT` (only-confirmed-seated + release frees + re-register). Note: run
      `reconcile-deposits` before `release-no-shows` so a genuine late deposit is seated, not released.
- [-] **3. Isolated settlement — CANCELLED (on-chain linkability).** Paying the prize by consolidating the
      dedicated wallets → winners necessarily **reveals the wallets' co-ownership on-chain** and so defeats the
      isolation: a batched Solana tx exposes co-signing (multiple source signatures = one controller, CIOH-style),
      a shared fee-payer links all settlement txs, and the convergence of many sources onto a winner clusters them
      regardless — the isolation only holds *at rest* (during play), not once funds move. Not worth the
      engineering for no real privacy gain. Consequence: the isolated variant has **no on-chain payout path** as
      designed; a different payout model would be needed (e.g. credit the prize off-chain to the winner's
      `WalletAccount` and let them withdraw via the standard Solana coordinator — which reintroduces a treasury/
      consolidation step anyway). Revisit only if a privacy-preserving payout (e.g. CoinJoin-style) is in scope.
- [x] **4. Refund/cancel (admin-approved)** — `FederationRefund` entity/status/repo + `FederationRefundService`
      (request / approve-with-address / reject / forSigning / recordBroadcast / confirm→wallet REFUNDED) +
      `SolRefundCoordinator` (buildUnsigned / broadcast / reconcile) + admin REST (request, approve, reject,
      sol-unsigned/broadcast/reconcile) + Liquibase 31 + `federated-isolated-refund-fee` (net = gross − fee).
      Nothing is signable until a moderator approves and supplies the destination. The refund SPL transfer has
      TWO offline signers — operator fee-payer + the dedicated-wallet owner (authority). Verified on H2
      (`FederationRefundIT`) **and end-to-end on `solana-test-validator`** (`FederationRefundValidatorIT`: fund a
      dedicated wallet → request → approve → offline two-signer sign → broadcast → reconcile → player's USDT
      balance moves + wallet REFUNDED). FIX along the way: `SolanaRpcClient.sendTransaction` now uses
      `preflightCommitment=confirmed` so a just-funded source account (visible at confirmed, not yet finalized)
      isn't rejected with a spurious `InvalidAccountData`. Original design notes follow:
      return a funded buy-in from its dedicated wallet to the player when
      the federation can't run (under-fill / cancelled) or a player un-registers before start. **Every refund
      requires admin approval** (a moderator gate, mirroring the withdrawal-approval flow: `PENDING_APPROVAL` →
      admin approves → assemble unsigned → offline ed25519-sign the dedicated wallet → broadcast → reconcile →
      `REFUNDED`). Per-wallet, **multi-key** (each wallet's own offline key signs), reusing
      `SolMessage`/`SolInstructions`/`SolTransaction`. Privacy: refunds **fan out to distinct players (no
      convergence)**, so the linkability is far softer than the cancelled slice 3 — do 1 tx/wallet, self-funded
      fee, refund to the deposit source → on-chain it's just the reverse of the already-public deposit. Open
      design: the refund destination (capture the deposit's on-chain sender via `getSignaturesForAddress`, vs a
      player-provided withdrawal address) — admin confirms it at approval. This is the **essential money-back
      safety path** (without it, funded players' USDT is stranded on cancel); it builds the (soft) offline-transfer
      infra slice 3 would have. Verify on `solana-test-validator`.
- [~] **5. Scale/ops** — *offline keygen-batching done; the rest later.*
    - [x] **Offline keygen-batching (1M)** — `OfflineDepositPoolGenerator` derives federation wallets by
      **absolute index** (`generateFederationWallets(seed, fedId, fromIndex, count, mint)`), so any chunk equals
      the matching slice of a whole-field generation and is re-runnable. CLI `--federation-id --mint --count
      --chunk` → `writeFederationWalletsChunked` streams `fedwallets-import-NNNNN.json` chunk files (bounded
      memory, one chunk in RAM at a time) + a `fedwallets-secret.txt` (seed/fed/mint, offline only). Import is
      **bulk-idempotent**: `FederationPlayerWalletService.importBatch` does one `findExistingAddresses` query +
      `saveAll`, so a 1M field imports chunk-by-chunk with re-import = 0 new. Keys re-derive offline via
      `federationWalletSeed(seed, fedId, index)`; nothing private touches the server. Tests:
      `OfflineFederationWalletGenTest` (chunk == slice, key re-derivation, chunked-file writer) +
      `FederationIsolatedWalletIT.importIsIdempotentAndBatched`.
    - [x] **ATA pre-creation + close (rent)** — exchanges send a bare SPL transfer that does NOT create the
      recipient ATA, so each dedicated wallet's USDT ATA must exist before its buy-in can land. `SolAtaProvisioner`
      assembles offline-signed batches: **create** (idempotent `createIdempotentAta` for FREE-buffer + ASSIGNED
      wallets needing one, rent paid by the operator = the only signer) and **close** (`closeAccount` on a finished
      wallet's empty ATA → rent reclaimed to the operator, signed by the operator fee-payer + each wallet owner).
      Wallets track `ataProvisioned`; batch size capped (`federated-isolated-ata-batch-size`, default 6) to stay
      within tx size/compute. Flow build→broadcast→confirm via admin endpoints (`/{id}/ata/create|close/unsigned`,
      `/ata/broadcast`, `/ata/{create,close}/confirm`), all flag-gated. **Rent economics:** ~0.00204 SOL/ATA, only
      for actual registrants (lazy buffer, not the whole 1M pool), and recovered on close. Proven on
      `solana-test-validator` (`SolAtaProvisionValidatorIT`): a pre-created ATA lets a bare exchange-style transfer
      land + seat; closing an empty ATA returns rent to the operator. (Read methods moved to `confirmed` commitment
      so a just-created mint/ATA is visible — the default `finalized` lagged.) Liquibase changeset 32.
    - [x] **Admin UI** — isolated-custody console on the admin-federation page (`IsolatedCustodyPanelComponent`):
      create toggle (isolated + USDT_SOL buy-in); import dedicated wallets (paste a generator chunk file, idempotent);
      ATA pre-create/close batches; reconcile deposits + release no-shows; admin-approved refunds
      (request/approve/reject + unsigned→broadcast→reconcile). Offline-signing ops are key-free: the console only
      triggers the backend build/broadcast/confirm endpoints and shows the raw JSON; the operator signs the
      `messageBase64` off-browser and pastes the signed tx back. Verified via AOT template build + lint.
    - [ ] Deposit polling at scale (scheduler/batched `getSignaturesForAddress`).

## TODO — tournament add-on (+ cash top-up)
Rebuy is done end-to-end (`POST /v1/tournaments/{id}/rebuy` → `TournamentService.processRebuy` → store
`requestRebuy` effect + lobby "Rebuy" button). **Add-on is modelled but not wired**: `Tournament.addOnAmount` /
`TournamentRegistration.getAddOnsUsed()` / `getTotalAddOns()` feed the prize-pool maths and the builder has
`.addOn(amount)`, but there is **no endpoint and no service method**, so a player cannot actually take one.
Add-on is a tournament-only concept (a one-time extra-chip purchase, usually offered at the end of the rebuy
period / first break) — it is **not** a cash-game mechanic.
- [ ] **Backend** — `TournamentService.processAddOn(tournamentId, playerId)`: validate it's an add-on-enabled
      tournament + the add-on window is open + not already used; grant `addOnAmount` chips, increment
      `addOnsUsed`. Mirror `processRebuy` (IllegalState → 409, ResourceNotFound → 404).
- [ ] **REST** — `POST /v1/tournaments/{id}/add-on` (`AddOnRequest{playerId}` → `AddOnResponse` with
      `newChipCount` / `addOnsUsed`), `@WebMvcTest` coverage in `TournamentControllerIT`.
- [ ] **Frontend** — `TournamentStore.requestAddOn` effect + an "Add-on" button/info in the tournament lobby
      (only when add-on-enabled + window open), by exact analogy with the rebuy slice. Surface `addOnsUsed`
      on `TournamentPlayer` (the leaderboard entry already carries it).
- [x] **Cash top-up (done)** — the cash analog of an add-on: `CashGameWalletService.topUp` debits the wallet and
      raises the seat's stack up to the table's max buy-in (ledger `CASH_BUYIN`, idempotent on seat id + pre-top-up
      buy-in total), allowed only between hands (`currentGameId == null`). `POST /v1/cash/tables/{id}/top-up`
      (`TopUpRequest{amount}` → `SitDownResponse`) + an "＋ Add chips" control in the cash-table component (shown when
      seated + no live hand). Verified by `CashGameWalletServiceIT` (4 cases) + `cash.service.spec` (endpoint wiring).

## TODO — UI / table polish
- [ ] **Port the table effects (Tier-1/2/3) to `tournament-table`** — winner glow + 🏆 badge, per-seat
      countdown bar (`.seat-timer`), bet-as-chip pop, deal-from-centre, board flip, dealer drop, avatar pulse
      ring, fold-to-muck, all-in flash/shove, felt + vignette were added to `game-table` only.
      `tournament-table` renders its own seats inline (the two tables don't share a seat component), so mirror
      the markup + SCSS there, gated by the animations toggle + `prefers-reduced-motion`. Best done by first
      extracting a shared `seat`/`player-plate` component so the effects live in one place.
- [x] **Tier-2 (done)** — `game-table`: cards deal in from table centre, the board reveals per street
      (flop/turn/river) with a staggered flip, and the dealer button animates to its new seat each hand.
- [x] **Tier-3 (done)** — `game-table`: fold → cards slide to the muck; all-in → plate flash + bet-chip shove;
      pulsing gold ring around the active player's avatar; richer felt + edge vignette on the table background.
- [x] **Tier-3 extras (done)** — `game-table`: pot-push chip travels toward the winning seat; pot + stack
      numbers count up (`CountUpDirective`); floating action labels ("Raise $X / Check / Fold") off `lastAction`;
      confetti burst when the local player wins.

## TODO — engine / domain refactor (defer to Phase G)
Betting-round state is currently scattered across mutable fields on the `PokerGame` aggregate (`currentBet`,
`minRaise`, `lastRaiseAmount`, `lastAggressorId`) **and** on each `Player` (`hasActed`, `betAmount`), mutated
in many places (`executeCall/Bet/Raise/Check`, `resetForNewBettingRound`, `resetActionsForRaise`,
`advancePhase`). Two production bugs were symptoms of this split state: `Player.hasActed` silently dropped from
the Redis hot-state JSON (non-standard getter → never serialized → flop never came, players cycled forever),
and the dead `actionsThisRound` counter that the mapper hardcoded to `0` (since removed). See
`AGGREGATE_MIGRATION_PLAN.md` Phase G.
- [ ] **Rich `Round` object (full stateful betting-round) — DEFERRED to Phase G.** A value/aggregate object
      that **owns the per-player contributions** and answers `isComplete()` itself, instead of the aggregate
      reaching into `Player.hasActed`/`betAmount` + `currentBet` + `lastAggressor`. Gives one source of truth,
      atomic round reset (no half-reset bugs), and isolated testability. **Do NOT do it while two engines
      coexist** (legacy + aggregate must stay byte-for-byte equal): it forces moving contribution state off
      `Player`, and the flat JPA `Game`/`Player` + Redis-JSON persistence fights a rich object (new mapping =
      new surface for the exact serialization bugs above). Natural moment: during Phase G, once `PokerGameService`
      becomes a thin orchestrator over the aggregate and there is a single engine path.
- [ ] **Cheap interim (optional, can do anytime):** extract the current `isBettingRoundComplete()` into a pure,
      stateless policy `boolean isComplete(List<Player> active, int currentBet)` with its own unit tests for the
      edge cases (BB option, all-in, 0-chip seat, raise re-open). Captures most of the testability win with
      ~zero risk; does not touch persistence or the `Player` model. Was considered low-ROI for now — revisit if
      betting-round completion bugs recur.
- Removed in the cleanup that surfaced this (commit `refactor(game): drop dead betting-round counter…`): the
  write-only `actionsThisRound` counter, `HandCompleted.totalActions`, `PokerGame.getCurrentBettingRound()`, and
  the unused `domain.value.BettingRound` value object (a half-measure — it didn't own players, so it couldn't
  decide completion). Re-introduce a *proper* owning `Round` per the first item above rather than restoring it.
