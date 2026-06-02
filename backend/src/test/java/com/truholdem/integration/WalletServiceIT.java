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
import com.truholdem.model.KycStatus;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.repository.KycRecordRepository;
import com.truholdem.repository.WalletAccountRepository;
import com.truholdem.repository.WalletLedgerEntryRepository;
import com.truholdem.repository.WithdrawalRequestRepository;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.service.wallet.WalletExceptions.InsufficientFundsException;
import com.truholdem.service.wallet.WalletExceptions.KycRequiredException;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.kyc-required-for-withdrawal=true"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Wallet: on-chain deposit + KYC-gated withdrawal")
class WalletServiceIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired
    private WalletService walletService;
    @Autowired
    private WalletAccountRepository accountRepository;
    @Autowired
    private WalletLedgerEntryRepository ledgerRepository;
    @Autowired
    private WithdrawalRequestRepository withdrawalRepository;
    @Autowired
    private KycRecordRepository kycRepository;

    @BeforeEach
    void clean() {
        ledgerRepository.deleteAll();
        withdrawalRepository.deleteAll();
        accountRepository.deleteAll();
        kycRepository.deleteAll();
    }

    @Test
    @DisplayName("on-chain deposit is credited exactly once per tx id (idempotent)")
    void depositIsIdempotent() {
        UUID user = UUID.randomUUID();

        assertThat(walletService.creditOnChainDeposit(user, ASSET, "tx-1", new BigDecimal("100"))).isTrue();
        // duplicate webhook / redelivery for the same transaction
        assertThat(walletService.creditOnChainDeposit(user, ASSET, "tx-1", new BigDecimal("100"))).isFalse();

        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("100");
        assertThat(ledgerRepository.findByUserIdAndAssetOrderByCreatedAtDesc(user, ASSET)).hasSize(1);
    }

    @Test
    @DisplayName("withdrawal is blocked until KYC is verified (no debit)")
    void withdrawalBlockedWithoutKyc() {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-2", new BigDecimal("50"));

        assertThatThrownBy(() -> walletService.requestWithdrawal(user, ASSET, "addr", new BigDecimal("10")))
                .isInstanceOf(KycRequiredException.class);

        assertThat(walletService.balance(user, ASSET)).as("balance untouched").isEqualByComparingTo("50");
        assertThat(walletService.withdrawals(user)).isEmpty();
    }

    @Test
    @DisplayName("withdrawal succeeds after KYC: debits balance and broadcasts")
    void withdrawalSucceedsAfterKyc() {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-3", new BigDecimal("50"));
        walletService.recordKycDecision(user, KycStatus.VERIFIED, "mock", "ref-1");

        WithdrawalRequest w = walletService.requestWithdrawal(user, ASSET, "addr-out", new BigDecimal("30"));

        assertThat(w.getStatus()).isEqualTo(WithdrawalStatus.BROADCAST);
        assertThat(w.getTxId()).isNotBlank();
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("withdrawal beyond balance is rejected (insufficient funds)")
    void insufficientFunds() {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-4", new BigDecimal("5"));
        walletService.recordKycDecision(user, KycStatus.VERIFIED, "mock", "ref-2");

        assertThatThrownBy(() -> walletService.requestWithdrawal(user, ASSET, "addr", new BigDecimal("10")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("5");
    }
}
