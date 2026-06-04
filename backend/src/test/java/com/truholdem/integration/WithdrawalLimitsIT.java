package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.service.wallet.WalletExceptions.WithdrawalCoolingPeriodException;
import com.truholdem.service.wallet.WalletExceptions.WithdrawalLimitExceededException;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.kyc-required-for-withdrawal=false",
        "app.payments.withdrawal-approval-required=true",
        "app.payments.withdrawal-cooling-period-minutes=10",
        "app.payments.max-withdrawal-per-tx.USDT_TRC20=100",
        "app.payments.max-withdrawal-per-day.USDT_TRC20=150" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Withdrawal limits + cooling period")
class WithdrawalLimitsIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired
    private WalletService walletService;

    private UUID user;

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("1000"));
    }

    @Test
    @DisplayName("a request above the per-transaction limit is rejected without debiting")
    void perTransactionLimit() {
        assertThatThrownBy(() -> walletService.requestWithdrawal(user, ASSET, "Taddr", new BigDecimal("101")))
                .isInstanceOf(WithdrawalLimitExceededException.class);
        assertThat(walletService.balance(user, ASSET)).as("not debited").isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("the rolling-24h limit accounts for prior pending withdrawals")
    void dailyLimit() {
        walletService.requestWithdrawal(user, ASSET, "Taddr", new BigDecimal("100")); // ok (≤ per-tx, ≤ daily)

        assertThatThrownBy(() -> walletService.requestWithdrawal(user, ASSET, "Taddr", new BigDecimal("60")))
                .isInstanceOf(WithdrawalLimitExceededException.class); // 100 + 60 > 150
        assertThat(walletService.balance(user, ASSET)).as("only the first debited").isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("approving within the cooling period is blocked")
    void coolingPeriod() {
        WithdrawalRequest w = walletService.requestWithdrawal(user, ASSET, "Taddr", new BigDecimal("50"));
        UUID moderator = UUID.randomUUID();

        assertThatThrownBy(() -> walletService.approveWithdrawal(w.getId(), moderator))
                .isInstanceOf(WithdrawalCoolingPeriodException.class);
    }
}
