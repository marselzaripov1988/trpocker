package com.truholdem.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;

@DisplayName("SelfCustodyEthPaymentProvider — provider-less ETH deposit addresses")
class SelfCustodyEthPaymentProviderTest {

    private SelfCustodyEthPaymentProvider provider;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getPayments().setProvider("eth-self-custody");
        props.getPayments().setSelfCustodyMasterKey("demo-master-key-not-for-prod");
        provider = new SelfCustodyEthPaymentProvider(props);
    }

    @Test
    @DisplayName("derives a valid EIP-55 ETH address per user, deterministic and distinct")
    void derivesDeterministicDistinctAddresses() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        String a = provider.allocateDepositAddress(userA, CryptoAsset.ETH);
        assertThat(a).matches("0x[0-9a-fA-F]{40}");
        assertThat(provider.allocateDepositAddress(userA, CryptoAsset.ETH)).as("deterministic").isEqualTo(a);
        assertThat(provider.allocateDepositAddress(userB, CryptoAsset.ETH)).as("distinct").isNotEqualTo(a);
    }

    @Test
    @DisplayName("rejects non-ETH assets")
    void rejectsNonEth() {
        assertThatThrownBy(() -> provider.allocateDepositAddress(UUID.randomUUID(), CryptoAsset.BTC))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("withdrawal broadcast is not wired (needs signer/HSM + node)")
    void withdrawalNotWired() {
        assertThatThrownBy(() -> provider.broadcastWithdrawal(
                UUID.randomUUID(), CryptoAsset.ETH, "0xabc", new BigDecimal("1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("requires a configured master key")
    void requiresMasterKey() {
        AppProperties props = new AppProperties();
        props.getPayments().setProvider("eth-self-custody");
        assertThatThrownBy(() -> new SelfCustodyEthPaymentProvider(props))
                .isInstanceOf(IllegalStateException.class);
    }
}
