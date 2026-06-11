package com.truholdem.service.wallet.btc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.wallet.BtcConfirmationDto;
import com.truholdem.dto.wallet.BtcSweepInputDto;
import com.truholdem.dto.wallet.BtcSweepUnsignedDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.DepositAddressPoolEntry;
import com.truholdem.model.DepositAddressStatus;
import com.truholdem.model.SweepBatch;
import com.truholdem.model.SweepBatchStatus;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.repository.SweepBatchRepository;

/**
 * Online half of a BTC deposit→treasury <b>sweep</b>: gathers UTXOs from the watch-only deposit-pool addresses
 * and assembles an <em>unsigned</em> P2WPKH transaction that consolidates them into the single treasury address
 * ({@code app.payments.btc-from-address}), broadcasts the offline-signed raw tx, and reconciles confirmations.
 * The private keys never touch the server — each input is signed offline with the key for its deposit address's
 * derivation index (carried per-input in {@link BtcSweepUnsignedDto}). This is an internal custody move: it does
 * not touch any {@code WalletAccount} or the user ledger, only the {@link SweepBatch} audit trail.
 *
 * <p>Active when {@code app.payments.btc-rpc-enabled=true}; each operation additionally requires
 * {@code app.payments.sweep.enabled=true} (checked at call time, off by default).
 */
@Component
@ConditionalOnProperty(name = "app.payments.btc-rpc-enabled", havingValue = "true")
public class BtcSweepCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BtcSweepCoordinator.class);

    // Rough P2WPKH vsize model (vbytes): tx overhead + per-input + per-output. Mirrors BtcWithdrawalCoordinator;
    // duplicated (≤6 lines) to keep zero blast radius on the withdrawal path — extract a shared BtcFee later.
    private static final long VSIZE_OVERHEAD = 11;
    private static final long VSIZE_PER_INPUT = 68;
    private static final long VSIZE_PER_OUTPUT = 31;
    private static final long DUST_SAT = 294; // P2WPKH dust threshold

    private final BtcRpcClient rpc;
    private final DepositAddressPoolRepository poolRepository;
    private final SweepBatchRepository sweepRepository;
    private final AppProperties appProperties;

    public BtcSweepCoordinator(BtcRpcClient rpc, DepositAddressPoolRepository poolRepository,
            SweepBatchRepository sweepRepository, AppProperties appProperties) {
        this.rpc = rpc;
        this.poolRepository = poolRepository;
        this.sweepRepository = sweepRepository;
        this.appProperties = appProperties;
    }

    /**
     * Plan + assemble an unsigned sweep for the asset: select up to {@code sweep.batch-max-inputs} confirmed
     * deposit-address UTXOs at or above {@code sweep.min-amount-per-asset}, consolidate them into a single
     * output to the treasury, persist a PLANNED {@link SweepBatch}, and return the unsigned tx for the offline
     * signer. Throws if nothing is sweepable or the consolidated output would be dust.
     */
    @Transactional
    public BtcSweepUnsignedDto planSweep(CryptoAsset asset) {
        requireSweepEnabled();
        if (asset != CryptoAsset.BTC) {
            throw new IllegalArgumentException("BTC sweep coordinator handles only BTC, not " + asset);
        }
        AppProperties.Payments p = appProperties.getPayments();
        String network = p.getBtcNetwork();
        String treasury = require(p.getBtcFromAddress(), "app.payments.btc-from-address");
        long feeRate = p.getBtcFeeRateSatPerVbyte();
        int maxInputs = p.getSweep().getBatchMaxInputs();
        long minAmountSat = minAmountSat(p, asset);

        // Gather candidate UTXOs across all ASSIGNED deposit addresses, tagged with their derivation index so the
        // offline signer can re-derive the per-input key.
        List<BtcSweepInputDto> candidates = new ArrayList<>();
        for (DepositAddressPoolEntry entry :
                poolRepository.findByAssetAndStatus(asset, DepositAddressStatus.ASSIGNED)) {
            for (BtcRpcClient.Utxo u : rpc.listUnspent(entry.getAddress())) {
                if (u.confirmations() >= p.getBtcMinUtxoConfirmations() && u.valueSat() >= minAmountSat) {
                    candidates.add(new BtcSweepInputDto(u.txid(), u.vout(), u.valueSat(),
                            u.scriptPubKeyHex(), entry.getDerivationIndex(), entry.getAddress()));
                }
            }
        }
        List<BtcSweepInputDto> chosen = candidates.stream()
                .sorted(Comparator.comparingLong(BtcSweepInputDto::valueSat).reversed())
                .limit(maxInputs)
                .toList();
        if (chosen.isEmpty()) {
            throw new IllegalStateException("Nothing to sweep for " + asset
                    + " (no ASSIGNED deposit UTXO ≥ " + minAmountSat + " sat with "
                    + p.getBtcMinUtxoConfirmations() + "+ confirmations)");
        }

        long inSum = chosen.stream().mapToLong(BtcSweepInputDto::valueSat).sum();
        long fee = feeFor(chosen.size(), 1, feeRate); // N inputs → 1 consolidated output
        long outValue = inSum - fee;
        if (outValue <= DUST_SAT) {
            throw new IllegalStateException("Sweep output would be dust: " + outValue + " sat (in " + inSum
                    + " − fee " + fee + ")");
        }

        SweepBatch batch = sweepRepository.save(new SweepBatch(asset, treasury, chosen.size(), inSum, fee));
        log.info("Planned BTC sweep {}: {} input(s) ({} sat) → {} ({} sat, fee {})",
                batch.getId(), chosen.size(), inSum, treasury, outValue, fee);
        return new BtcSweepUnsignedDto(batch.getId(), network, 2, 0L, fee, treasury, outValue, chosen);
    }

    /** Broadcast the offline-signed raw sweep tx and record its txid → BROADCAST. */
    @Transactional
    public SweepBatch broadcast(UUID sweepBatchId, String signedRawTxHex) {
        requireSweepEnabled();
        SweepBatch batch = load(sweepBatchId);
        String txid = rpc.sendRawTransaction(signedRawTxHex);
        batch.markBroadcast(txid);
        log.info("Sweep {} broadcast on-chain (tx {})", sweepBatchId, txid);
        return sweepRepository.save(batch);
    }

    /** Read-only confirmation status of a txid against the threshold. */
    public BtcConfirmationDto confirmation(String txid) {
        long confirmations = rpc.confirmations(txid);
        boolean confirmed = confirmations >= appProperties.getPayments().getMinConfirmations();
        return new BtcConfirmationDto(txid, confirmations, confirmed);
    }

    /** Reconcile a BROADCAST sweep → CONFIRMED once its tx has enough confirmations. Idempotent. */
    @Transactional
    public SweepBatch reconcile(UUID sweepBatchId) {
        SweepBatch batch = load(sweepBatchId);
        if (batch.getStatus() != SweepBatchStatus.BROADCAST || batch.getTxId() == null) {
            return batch;
        }
        if (confirmation(batch.getTxId()).confirmed()) {
            batch.markConfirmed();
            sweepRepository.save(batch);
        }
        return batch;
    }

    private SweepBatch load(UUID sweepBatchId) {
        return sweepRepository.findById(sweepBatchId)
                .orElseThrow(() -> new NoSuchElementException("Sweep batch not found: " + sweepBatchId));
    }

    private void requireSweepEnabled() {
        AppProperties.Payments p = appProperties.getPayments();
        if (!p.isEnabled() || !p.getSweep().isEnabled()) {
            throw new IllegalStateException("Deposit→treasury sweep is disabled (app.payments.sweep.enabled)");
        }
    }

    private static long minAmountSat(AppProperties.Payments p, CryptoAsset asset) {
        BigDecimal min = p.getSweep().getMinAmountPerAsset().get(asset.name());
        return min == null ? 0L : min.movePointRight(8).longValueExact();
    }

    private static long feeFor(int nIn, int nOut, long feeRate) {
        return (VSIZE_OVERHEAD + (long) nIn * VSIZE_PER_INPUT + (long) nOut * VSIZE_PER_OUTPUT) * feeRate;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the BTC sweep coordinator");
        }
        return value;
    }
}
