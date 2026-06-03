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

/**
 * Offline-signer (PSBT) handoff: with the offline-pool provider, approving a withdrawal cannot broadcast
 * in-process, so it stays APPROVED; the unsigned intent is exported, signed + broadcast offline, and the tx
 * id is recorded back → BROADCAST.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.provider=offline-pool",
        "app.payments.kyc-required-for-withdrawal=false",
        "app.payments.withdrawal-approval-required=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Withdrawal offline-signer (PSBT) handoff")
class WithdrawalSigningHandoffIT {

    private static final CryptoAsset ASSET = CryptoAsset.BTC;

    @Autowired
    private WalletService walletService;

    private UUID user;
    private final UUID moderator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("1"));
    }

    private WithdrawalRequest approvedWithdrawal() {
        WithdrawalRequest w = walletService.requestWithdrawal(user, ASSET, "bc1qdest", new BigDecimal("0.4"));
        return walletService.approveWithdrawal(w.getId(), moderator);
    }

    @Test
    @DisplayName("approve leaves an offline-pool withdrawal APPROVED (not broadcast)")
    void approveAwaitsOfflineSigning() {
        WithdrawalRequest approved = approvedWithdrawal();

        assertThat(approved.getStatus()).isEqualTo(WithdrawalStatus.APPROVED);
        assertThat(approved.getTxId()).isNull();
    }

    @Test
    @DisplayName("export the unsigned intent, then record the broadcast tx id → BROADCAST")
    void exportThenRecordBroadcast() {
        WithdrawalRequest approved = approvedWithdrawal();

        WithdrawalRequest forSigning = walletService.withdrawalForSigning(approved.getId());
        assertThat(forSigning.getToAddress()).isEqualTo("bc1qdest");
        assertThat(forSigning.getAmount()).isEqualByComparingTo("0.4");

        WithdrawalRequest broadcast = walletService.recordBroadcast(approved.getId(), "signed-tx-hash");

        assertThat(broadcast.getStatus()).isEqualTo(WithdrawalStatus.BROADCAST);
        assertThat(broadcast.getTxId()).isEqualTo("signed-tx-hash");
    }

    @Test
    @DisplayName("export/record require an APPROVED request (not once broadcast)")
    void guardsNonApproved() {
        WithdrawalRequest approved = approvedWithdrawal();
        walletService.recordBroadcast(approved.getId(), "signed-tx-hash"); // now BROADCAST

        assertThatThrownBy(() -> walletService.withdrawalForSigning(approved.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> walletService.recordBroadcast(approved.getId(), "again"))
                .isInstanceOf(IllegalStateException.class);
    }
}
