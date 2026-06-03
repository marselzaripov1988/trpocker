package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;

/**
 * Ingests on-chain deposits detected by a watch-only watcher (a node/indexer scanning the pooled addresses).
 * Such a watcher knows the destination <em>address</em>, not the user — so this resolves the owning user from
 * the {@link DepositAddressPoolService} and credits idempotently by tx id (via {@link WalletService}). Credit
 * is withheld until the deposit reaches {@code app.payments.min-confirmations}.
 */
@Service
public class DepositIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DepositIngestionService.class);

    /** Outcome of ingesting one detected deposit. */
    public enum Result {
        CREDITED, DUPLICATE, PENDING_CONFIRMATIONS, UNKNOWN_ADDRESS
    }

    private final DepositAddressPoolService pool;
    private final WalletService walletService;
    private final AppProperties appProperties;

    public DepositIngestionService(DepositAddressPoolService pool, WalletService walletService,
            AppProperties appProperties) {
        this.pool = pool;
        this.walletService = walletService;
        this.appProperties = appProperties;
    }

    /** Credit a deposit detected at {@code address}. Idempotent by {@code txId}; no-op below the confirmation
     *  threshold or for an address not assigned to any user. */
    @Transactional
    public Result ingest(CryptoAsset asset, String address, String txId, BigDecimal amount, int confirmations) {
        int min = Math.max(0, appProperties.getPayments().getMinConfirmations());
        if (confirmations < min) {
            log.info("Deposit {} to {} has {} confirmations (< {}) — waiting", txId, address, confirmations, min);
            return Result.PENDING_CONFIRMATIONS;
        }
        Optional<UUID> user = pool.assignedUser(asset, address);
        if (user.isEmpty()) {
            log.warn("Deposit {} to unknown/unassigned {} address {} — ignored", txId, asset, address);
            return Result.UNKNOWN_ADDRESS;
        }
        boolean applied = walletService.creditOnChainDeposit(user.get(), asset, txId, amount);
        return applied ? Result.CREDITED : Result.DUPLICATE;
    }
}
