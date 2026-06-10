package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.DepositAddressStatus;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.repository.WalletAccountRepository;
import com.truholdem.repository.WithdrawalRequestRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Read-only Micrometer gauges over the money tables, for alerting. Deliberately isolated from the wallet
 * services (it only reads repository counts) so it cannot affect the money paths themselves. Each gauge is
 * evaluated at scrape time and is defensive: a transient DB error yields {@code NaN} rather than breaking the
 * whole {@code /actuator/prometheus} endpoint.
 *
 * <p>Beyond the pool/pipeline counters, this exposes the <b>solvency</b> view per asset: the platform's total
 * liability to users (Σ of all wallet balances) plus in-flight withdrawal amounts, against the operator-declared
 * on-chain reserve float ({@code app.payments.reserve-float}). The wallet {@code balance} is an internal
 * custodial IOU ledger, not a mirror of on-chain funds; nothing in the app reads the treasury balance (keys are
 * offline / addresses are watch-only), so the float is an operator input and the solvency alert fires when
 * liabilities approach it. This closes the documented gap where withdrawals could silently exceed available funds.
 */
@Component
public class WalletMetrics {

    /** Withdrawals not yet in a terminal state — the in-flight payout pipeline. */
    private static final List<WithdrawalStatus> IN_FLIGHT = List.of(
            WithdrawalStatus.PENDING_APPROVAL, WithdrawalStatus.APPROVED, WithdrawalStatus.BROADCAST);

    public WalletMetrics(DepositAddressPoolRepository poolRepository,
            WithdrawalRequestRepository withdrawalRepository,
            WalletAccountRepository walletAccountRepository,
            AppProperties appProperties,
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

            // Solvency view (per asset). liabilities = Σ of every user's balance for the asset — what we owe.
            Gauge.builder("truholdem.wallet.liabilities",
                            () -> safeAmount(() -> walletAccountRepository.sumBalanceByAsset(asset)))
                    .description("Total user balance held for this asset — the platform's aggregate liability (what we owe).")
                    .baseUnit(asset.getSymbol())
                    .tag("asset", asset.name())
                    .register(meterRegistry);

            // In-flight payout commitment for the asset (amount, not count): funds promised but not yet settled.
            Gauge.builder("truholdem.wallet.withdrawals.in_flight_amount",
                            () -> safeAmount(() -> withdrawalRepository.sumAmountByAssetAndStatusIn(asset, IN_FLIGHT)))
                    .description("Total amount of in-flight withdrawals (PENDING_APPROVAL / APPROVED / BROADCAST) for this asset.")
                    .baseUnit(asset.getSymbol())
                    .tag("asset", asset.name())
                    .register(meterRegistry);

            // Operator-declared custodied on-chain float for the asset (treasury hot+cold). The reference the
            // solvency alert compares liabilities+in-flight against. NaN when unset → the alert stays dormant.
            Gauge.builder("truholdem.wallet.reserve_float",
                            () -> safeAmount(() -> reserveFloatFor(appProperties, asset)))
                    .description("Operator-declared custodied on-chain reserve float for this asset (app.payments.reserve-float). NaN if unset.")
                    .baseUnit(asset.getSymbol())
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

    /** Configured reserve float for the asset, or {@code null} if the operator has not declared one. */
    private static BigDecimal reserveFloatFor(AppProperties appProperties, CryptoAsset asset) {
        Map<String, BigDecimal> floats = appProperties.getPayments().getReserveFloat();
        return floats == null ? null : floats.get(asset.name());
    }

    private static double safeCount(LongSupplier query) {
        try {
            return query.getAsLong();
        } catch (RuntimeException e) {
            // Don't let a transient DB hiccup at scrape time break the whole metrics endpoint.
            return Double.NaN;
        }
    }

    /** A money amount as a double for the gauge, with {@code null}/error → {@code NaN} (so the metric is absent
     *  rather than a misleading 0, and a scrape-time DB hiccup never breaks the endpoint). */
    private static double safeAmount(Supplier<BigDecimal> query) {
        try {
            BigDecimal value = query.get();
            return value == null ? Double.NaN : value.doubleValue();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
