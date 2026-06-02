package com.truholdem.service.wallet;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;
import com.truholdem.repository.KycRecordRepository;
import com.truholdem.repository.WalletAccountRepository;
import com.truholdem.repository.WalletLedgerEntryRepository;
import com.truholdem.repository.WithdrawalRequestRepository;
import com.truholdem.service.wallet.WalletExceptions.PaymentsDisabledException;

@DisplayName("WalletService — disabled feature flag")
class WalletServiceDisabledTest {

    @Test
    @DisplayName("deposit and withdrawal are rejected when payments are disabled")
    void rejectsWhenDisabled() {
        AppProperties props = new AppProperties(); // payments.enabled defaults to false
        WalletService service = new WalletService(
                Mockito.mock(WalletAccountRepository.class),
                Mockito.mock(WalletLedgerEntryRepository.class),
                Mockito.mock(WithdrawalRequestRepository.class),
                Mockito.mock(KycRecordRepository.class),
                Mockito.mock(CryptoPaymentProvider.class),
                props);

        UUID user = UUID.randomUUID();
        assertThatThrownBy(() -> service.creditOnChainDeposit(user, CryptoAsset.BTC, "tx", new BigDecimal("1")))
                .isInstanceOf(PaymentsDisabledException.class);
        assertThatThrownBy(() -> service.requestWithdrawal(user, CryptoAsset.BTC, "addr", new BigDecimal("1")))
                .isInstanceOf(PaymentsDisabledException.class);
    }
}
