package com.truholdem.service.wallet;

import java.util.List;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.DepositAddressStatus;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.repository.WithdrawalRequestRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Read-only Micrometer gauges over the money tables, for alerting. Deliberately isolated from the wallet
 * services (it only reads repository counts) so it cannot affect the money paths themselves. Each gauge is
 * evaluated at scrape time and is defensive: a transient DB error yields {@code NaN} rather than breaking the
 * whole {@code /actuator/prometheus} endpoint.
 */
@Component
public class WalletMetrics {

    /** Withdrawals not yet in a terminal state — the in-flight payout pipeline. */
    private static final List<WithdrawalStatus> IN_FLIGHT = List.of(
            WithdrawalStatus.PENDING_APPROVAL, WithdrawalStatus.APPROVED, WithdrawalStatus.BROADCAST);

    public WalletMetrics(DepositAddressPoolRepository poolRepository,
            WithdrawalRequestRepository withdrawalRepository,
            MeterRegistry meterRegistry) {

        // Free deposit addresses left in the pool, per asset. Alert before it runs dry — an empty pool means new
        // users (or new assets) cannot be handed a deposit address.
        for (CryptoAsset asset : CryptoAsset.values()) {
            Gauge.builder("truholdem.wallet.deposit_pool.free",
                            () -> safeCount(() -> poolRepository.countByAssetAndStatus(asset, DepositAddressStatus.FREE)))
                    .description("Unassigned deposit addresses remaining in the pool for this asset.")
                    .tag("asset", asset.name())
                    .register(meterRegistry);
            // assigned > 0 marks an asset as actually in use, so a low-pool alert can ignore assets never imported.
            Gauge.builder("truholdem.wallet.deposit_pool.assigned",
                            () -> safeCount(() -> poolRepository.countByAssetAndStatus(asset, DepositAddressStatus.ASSIGNED)))
                    .description("Deposit addresses currently assigned to a user for this asset.")
                    .tag("asset", asset.name())
                    .register(meterRegistry);
        }

        // Withdrawals in flight (awaiting approval / broadcast / confirmation). A growing backlog means the payout
        // pipeline (manual approval or broadcasting) is stuck.
        Gauge.builder("truholdem.wallet.withdrawals.pending",
                        () -> safeCount(() -> withdrawalRepository.countByStatusIn(IN_FLIGHT)))
                .description("Withdrawals in flight (PENDING_APPROVAL / APPROVED / BROADCAST).")
                .register(meterRegistry);

        // Terminal failures. Cumulative, so alert on increase() — a rising count means payouts are failing at
        // broadcast / on-chain (each failure triggers a balance reversal).
        Gauge.builder("truholdem.wallet.withdrawals.failed",
                        () -> safeCount(() -> withdrawalRepository.countByStatus(WithdrawalStatus.FAILED)))
                .description("Withdrawals that ended in FAILED (cumulative). Alert on increase().")
                .register(meterRegistry);
    }

    private static double safeCount(LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (RuntimeException e) {
            // Don't let a transient DB hiccup at scrape time break the whole metrics endpoint.
            return Double.NaN;
        }
    }
}
