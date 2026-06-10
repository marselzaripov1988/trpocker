package com.truholdem.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.repository.WalletAccountRepository;
import com.truholdem.repository.WithdrawalRequestRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Verifies the solvency gauges WalletMetrics publishes: liabilities (Σ balances), in-flight withdrawal amount,
 * and the operator-declared reserve float — including the NaN-on-error / NaN-when-unset safety that keeps a
 * scrape-time hiccup or a missing config from breaking the endpoint or firing the solvency alert.
 */
@ExtendWith(MockitoExtension.class)
class WalletMetricsTest {

    @Mock(lenient = true)
    private DepositAddressPoolRepository poolRepository;
    @Mock(lenient = true)
    private WithdrawalRequestRepository withdrawalRepository;
    @Mock(lenient = true)
    private WalletAccountRepository walletAccountRepository;

    private AppProperties appProperties;
    private MeterRegistry registry;

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        registry = new SimpleMeterRegistry();
    }

    private void build() {
        new WalletMetrics(poolRepository, withdrawalRepository, walletAccountRepository, appProperties, registry);
    }

    private double gauge(String name) {
        return Search.in(registry).name(name).tag("asset", ASSET.name()).gauge().value();
    }

    @Test
    void liabilitiesGaugeReflectsSummedBalances() {
        when(walletAccountRepository.sumBalanceByAsset(ASSET)).thenReturn(new BigDecimal("1234.50"));
        build();
        assertThat(gauge("truholdem.wallet.liabilities")).isEqualTo(1234.50);
    }

    @Test
    void inFlightAmountGaugeReflectsSummedPendingWithdrawals() {
        when(withdrawalRepository.sumAmountByAssetAndStatusIn(eq(ASSET), any()))
                .thenReturn(new BigDecimal("300"));
        build();
        assertThat(gauge("truholdem.wallet.withdrawals.in_flight_amount")).isEqualTo(300.0);
    }

    @Test
    void reserveFloatGaugeReflectsConfiguredValue() {
        appProperties.getPayments().setReserveFloat(Map.of(ASSET.name(), new BigDecimal("50000")));
        build();
        assertThat(gauge("truholdem.wallet.reserve_float")).isEqualTo(50000.0);
    }

    @Test
    void reserveFloatGaugeIsNaNWhenUnset() {
        // No reserve-float configured for the asset → NaN, so the solvency alert (gated on >0) stays dormant.
        build();
        assertThat(gauge("truholdem.wallet.reserve_float")).isNaN();
    }

    @Test
    void liabilitiesGaugeIsNaNOnRepositoryError() {
        when(walletAccountRepository.sumBalanceByAsset(ASSET))
                .thenThrow(new RuntimeException("transient DB error at scrape time"));
        build();
        assertThat(gauge("truholdem.wallet.liabilities")).isNaN();
    }

    @Test
    void liabilitiesGaugeIsNaNWhenSumIsNull() {
        // COALESCE guards this in SQL, but a null must never surface as a misleading 0.
        when(walletAccountRepository.sumBalanceByAsset(ASSET)).thenReturn(null);
        build();
        assertThat(gauge("truholdem.wallet.liabilities")).isNaN();
    }

    @Test
    void inFlightAmountQueriesOnlyNonTerminalStatuses() {
        @SuppressWarnings("unchecked")
        Collection<WithdrawalStatus>[] captured = new Collection[1];
        when(withdrawalRepository.sumAmountByAssetAndStatusIn(eq(ASSET), any())).thenAnswer(inv -> {
            captured[0] = inv.getArgument(1);
            return BigDecimal.ZERO;
        });
        build();
        // Force gauge evaluation.
        gauge("truholdem.wallet.withdrawals.in_flight_amount");
        assertThat(captured[0]).containsExactlyInAnyOrder(
                WithdrawalStatus.PENDING_APPROVAL, WithdrawalStatus.APPROVED, WithdrawalStatus.BROADCAST);
        assertThat(captured[0]).doesNotContain(
                WithdrawalStatus.CONFIRMED, WithdrawalStatus.FAILED, WithdrawalStatus.REJECTED);
    }
}
