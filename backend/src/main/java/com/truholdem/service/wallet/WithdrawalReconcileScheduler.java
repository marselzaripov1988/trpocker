package com.truholdem.service.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.service.wallet.btc.BtcWithdrawalCoordinator;
import com.truholdem.service.wallet.eth.EthWithdrawalCoordinator;
import com.truholdem.service.wallet.sol.SolWithdrawalCoordinator;

/**
 * Periodically reconciles BROADCAST withdrawals against the chain so they reach their terminal state without
 * a manual poke: each is dispatched to the matching coordinator (ETH/ERC-20 or BTC), which moves it to
 * CONFIRMED once it has enough confirmations (or FAILED on an ETH revert). Each {@code reconcile} is
 * idempotent — once a withdrawal is terminal it is a no-op, and the {@code @Version} on the entity guards
 * concurrent saves — so this is safe to run on every node in a cluster. Inert unless payments are enabled and
 * {@code app.payments.withdrawal-reconcile-enabled=true}; a missing coordinator (its RPC flag off) is skipped.
 */
@Component
public class WithdrawalReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalReconcileScheduler.class);

    private final WalletService walletService;
    private final AppProperties appProperties;
    private final ObjectProvider<EthWithdrawalCoordinator> ethCoordinator;
    private final ObjectProvider<BtcWithdrawalCoordinator> btcCoordinator;
    private final ObjectProvider<SolWithdrawalCoordinator> solCoordinator;

    public WithdrawalReconcileScheduler(WalletService walletService, AppProperties appProperties,
            ObjectProvider<EthWithdrawalCoordinator> ethCoordinator,
            ObjectProvider<BtcWithdrawalCoordinator> btcCoordinator,
            ObjectProvider<SolWithdrawalCoordinator> solCoordinator) {
        this.walletService = walletService;
        this.appProperties = appProperties;
        this.ethCoordinator = ethCoordinator;
        this.btcCoordinator = btcCoordinator;
        this.solCoordinator = solCoordinator;
    }

    @Scheduled(fixedDelayString = "${app.payments.withdrawal-reconcile-interval-ms:60000}")
    public void reconcileBroadcast() {
        AppProperties.Payments p = appProperties.getPayments();
        if (!p.isEnabled() || !p.isWithdrawalReconcileEnabled()) {
            return;
        }
        EthWithdrawalCoordinator eth = ethCoordinator.getIfAvailable();
        BtcWithdrawalCoordinator btc = btcCoordinator.getIfAvailable();
        SolWithdrawalCoordinator sol = solCoordinator.getIfAvailable();
        for (WithdrawalRequest w : walletService.withdrawalsForReview(WithdrawalStatus.BROADCAST)) {
            try {
                reconcileOne(w, eth, btc, sol);
            } catch (RuntimeException e) {
                log.warn("Reconcile of broadcast withdrawal {} failed (will retry next sweep)", w.getId(), e);
            }
        }
    }

    private void reconcileOne(WithdrawalRequest w, EthWithdrawalCoordinator eth, BtcWithdrawalCoordinator btc,
            SolWithdrawalCoordinator sol) {
        switch (w.getAsset()) {
            case ETH, USDT_ERC20 -> {
                if (eth != null) {
                    eth.reconcile(w.getId());
                }
            }
            case BTC -> {
                if (btc != null) {
                    btc.reconcile(w.getId());
                }
            }
            case USDT_SOL -> {
                if (sol != null) {
                    sol.reconcile(w.getId());
                }
            }
            default -> {
                // No online coordinator for this asset (e.g. TRON/LTC) — left for manual / future reconcile.
            }
        }
    }
}
