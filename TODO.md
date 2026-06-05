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
- [ ] **2. Seat/session model** — `CashSeat` (table, user, stack, seatNo, sittingOut) + repository; map a money
      buy-in to engine chips (define chip↔asset scale).
- [ ] **3. Sit-down (buy-in)** — `CashGameWalletService.buyIn`: debit `WalletAccount`, seat the player with a
      stack, ledger entry. Validate min/max buy-in, seat availability, single-seat-per-table.
- [ ] **4. Stand-up (cash-out)** — credit the remaining stack back to the wallet on leave; ledger entry;
      handle leaving mid-hand (fold + cash out after hand).
- [ ] **5. Rake** — take a % (with cap) from each contested pot on showdown; record house revenue.
- [ ] **6. Engine wiring** — join/leave a live table mid-session (the engine currently seats all players at
      `createNewGame`); reconcile with the cluster hot-state/ownership model.
- [ ] **7. REST API** — list tables, sit/leave, table state; secure + flag-gated.
- [ ] **8. Lobby + table UI** — browse cash tables with stakes, pick buy-in, sit/leave with a stack.
- [ ] **9. Verify** — full suite + fresh-Postgres cluster + an end-to-end buy-in→play→cash-out IT.
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
- [ ] **2. Model + flag** — `pyramidBuyUpEnabled` flag (or new type) on Tournament + a `PyramidBuyout` record
      (tournament, level, seat/feeder index, buyer, price, asset) + repository + Liquibase changeset.
- [ ] **3. Buy-seat API + charge** — pre-start endpoint: validate empty sub-tree + cap + level range, charge
      the wallet `buyoutPrice`, persist the buyout. Idempotent per (tournament, seat).
- [ ] **4. Fixed-bracket seating at start** — switch this variant from the dynamic pyramid to the fixed
      bracket; at start, seat registered players at level 1 and bought players directly at their level
      (skipping the closed sub-trees).
- [ ] **5. Engine integration** — advancement through the fixed bracket; bought players already in place;
      reconcile with the existing `PyramidTournamentService` round/advance logic + cluster ownership.
- [ ] **6. Refund/edge** — if the tournament is cancelled, refund buy-outs too; what if it never fills.
- [ ] **7. UI + verify** — admin/player buy-seat UI; end-to-end IT.
Open: is the buyer a registered player or any user? exact cap semantics (per-buyer vs total)? does a bought
player still pay the normal buy-in too, or only the buy-out price? real-money only (needs `cryptoBuyInAmount`).

## TODO — cross-cutting / production-readiness
- [ ] Live AWS-KMS-backed `KycKeyProvider` is done; add a **hot-float / treasury balance monitor + alert**
      so withdrawals can't silently exceed available on-chain funds.
- [ ] On-chain/AML screening (Chainalysis/Elliptic) on the deposit + withdrawal paths.
- [ ] Per-coordinator metrics (broadcast latency, reconcile lag, pending-confirmations gauge).
- [ ] Decide on 4-eyes (2-of-N moderator approval) — intentionally NOT done; revisit if compliance requires.
