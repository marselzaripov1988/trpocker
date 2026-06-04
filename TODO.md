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
- [ ] Deposit address as a **QR code** (ties into the air-gap QR TODO; needs a QR lib or inline generator).
- [ ] KYC as a guided flow on `/wallet` (status → upload → pending → verified), not just the standalone page.
- [ ] Admin: **status filter** dropdown on `/admin/withdrawals` (backend already accepts `?status=`).
- [ ] Admin: **chain-specific signing workflow** UI (eth-/btc-unsigned → broadcast → reconcile/confirmation).
- [ ] Admin: **deposit-pool dashboard** (`GET /deposit-pool/status`) + batch import UI.
- [ ] Admin: **KYC re-encrypt** button (`POST /kyc/re-encrypt`).
- [ ] UX polish: withdrawal status polling, asset selector niceties, toasts, upload progress.

## TODO — cross-cutting / production-readiness
- [ ] Live AWS-KMS-backed `KycKeyProvider` is done; add a **hot-float / treasury balance monitor + alert**
      so withdrawals can't silently exceed available on-chain funds.
- [ ] On-chain/AML screening (Chainalysis/Elliptic) on the deposit + withdrawal paths.
- [ ] Per-coordinator metrics (broadcast latency, reconcile lag, pending-confirmations gauge).
- [ ] Decide on 4-eyes (2-of-N moderator approval) — intentionally NOT done; revisit if compliance requires.
