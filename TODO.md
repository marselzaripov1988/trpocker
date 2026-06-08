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
      pays the pool — `federated-shard-prize-bps` split among shard winners (qualifier) + remainder to the
      champion (rounding absorbed into champion; sums to pool). Verified by `FederatedPyramidPrizeIT`
      (exact balances + pool conservation).
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
- [x] **3. Admin prize distribution** — `distributeFederationPrizes(fedId, shardBps)` + admin endpoint
      `POST …/distribute?shardBps=N`: pool = **expected buy-ins** (`shardCount × shardSize × buyIn`, guaranteed),
      admin-chosen shard-winner share split among winners + remainder to champion (idempotent; shares one
      `payPool` core with the plain auto-payout). Verified by `FederatedPyramidPayoutIT`.
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
- [ ] Live AWS-KMS-backed `KycKeyProvider` is done; add a **hot-float / treasury balance monitor + alert**
      so withdrawals can't silently exceed available on-chain funds.
- [ ] On-chain/AML screening (Chainalysis/Elliptic) on the deposit + withdrawal paths.
- [ ] Per-coordinator metrics (broadcast latency, reconcile lag, pending-confirmations gauge).
- [ ] Decide on 4-eyes (2-of-N moderator approval) — intentionally NOT done; revisit if compliance requires.

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
- [ ] **Port Tier-1 table animations to `tournament-table`** — the winner glow + 🏆 badge, the per-seat
      countdown bar (`.seat-timer`), and bet-as-chip pop were added to `game-table` only. `tournament-table`
      renders its own seats inline (the two tables don't share a seat component), so mirror the same markup +
      SCSS there, gated by the animations toggle + `prefers-reduced-motion`. Consider extracting a shared
      `seat`/`player-plate` component to avoid the duplication.
- [x] **Tier-2 (done)** — `game-table`: cards deal in from table centre, the board reveals per street
      (flop/turn/river) with a staggered flip, and the dealer button animates to its new seat each hand.
- [ ] **Tier-3 (optional)** — fold → cards slide to the muck; all-in → stack shove; pulse ring around the
      active player's avatar; felt texture / vignette on the table background.
