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
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.kyc-required-for-withdrawal=false",
        "app.payments.withdrawal-approval-required=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Manual withdrawal approval")
class WithdrawalApprovalIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired
    private WalletService walletService;

    private UUID user;
    private final UUID moderator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("100"));
    }

    private WithdrawalRequest request() {
        return walletService.requestWithdrawal(user, ASSET, "Taddr", new BigDecimal("40"));
    }

    @Test
    @DisplayName("a request is debited and parked in PENDING_APPROVAL (not broadcast)")
    void parksForApproval() {
        WithdrawalRequest w = request();

        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.PENDING_APPROVAL);
        assertThat(w.getTxId()).isNull();
        assertThat(walletService.balance(user, ASSET)).as("debited up front").isEqualByComparingTo("60");
        assertThat(walletService.pendingApprovals()).extracting(WithdrawalRequest::getId).contains(w.getId());
    }

    @Test
    @DisplayName("approve broadcasts (mock provider) and records the moderator")
    void approveBroadcasts() {
        WithdrawalRequest w = request();

        WithdrawalRequest approved = walletService.approveWithdrawal(w.getId(), moderator);

        assertThat(approved.getStatus()).isEqualTo(WithdrawalStatus.BROADCAST);
        assertThat(approved.getTxId()).isNotBlank();
        assertThat(approved.getReviewedBy()).isEqualTo(moderator);
        assertThat(walletService.balance(user, ASSET)).as("stays debited").isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("reject reverses the debit and records the reason")
    void rejectReverses() {
        WithdrawalRequest w = request();

        WithdrawalRequest rejected = walletService.rejectWithdrawal(w.getId(), moderator, "address flagged");

        assertThat(rejected.getStatus()).isEqualTo(WithdrawalStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("address flagged");
        assertThat(rejected.getReviewedBy()).isEqualTo(moderator);
        assertThat(walletService.balance(user, ASSET)).as("debit reversed").isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("only a pending request can be approved/rejected")
    void rejectsActionOnNonPending() {
        WithdrawalRequest w = request();
        walletService.approveWithdrawal(w.getId(), moderator); // now BROADCAST

        assertThatThrownBy(() -> walletService.approveWithdrawal(w.getId(), moderator))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> walletService.rejectWithdrawal(w.getId(), moderator, "too late"))
                .isInstanceOf(IllegalStateException.class);
    }
}
