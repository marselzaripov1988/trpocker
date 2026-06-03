package com.truholdem.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.model.CryptoAsset;
import com.truholdem.service.wallet.crypto.EthKeys;
import com.truholdem.service.wallet.crypto.TronKeys;
import com.truholdem.tools.OfflineDepositPoolGenerator.Batch;

@DisplayName("Offline deposit-address pool generator")
class OfflineDepositPoolGeneratorTest {

    private static final byte[] SEED = "truholdem-test-seed".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("same seed reproduces identical addresses (backup = one seed)")
    void deterministicFromSeed() {
        Batch a = OfflineDepositPoolGenerator.generate(SEED, CryptoAsset.ETH, 5);
        Batch b = OfflineDepositPoolGenerator.generate(SEED, CryptoAsset.ETH, 5);

        assertThat(a.keys()).extracting(OfflineDepositPoolGenerator.Keypair::address)
                .isEqualTo(b.keys().stream().map(OfflineDepositPoolGenerator.Keypair::address).toList());
    }

    @Test
    @DisplayName("addresses are distinct per index and valid EIP-55")
    void distinctAndValid() {
        Batch batch = OfflineDepositPoolGenerator.generate(SEED, CryptoAsset.ETH, 10);

        assertThat(batch.keys()).hasSize(10);
        assertThat(batch.keys()).allSatisfy(k ->
                assertThat(EthKeys.isValidChecksumAddress(k.address())).as("valid EIP-55: %s", k.address()).isTrue());
        long distinct = batch.keys().stream().map(OfflineDepositPoolGenerator.Keypair::address)
                .collect(Collectors.toSet()).size();
        assertThat(distinct).as("all 10 addresses distinct").isEqualTo(10);
    }

    @Test
    @DisplayName("address at index i matches the documented EthKeys derivation")
    void matchesDocumentedDerivation() {
        Batch batch = OfflineDepositPoolGenerator.generate(SEED, CryptoAsset.ETH, 1);

        String expected = EthKeys.addressFromPrivateKey(EthKeys.derivePrivateKey(SEED, "eth/0"));
        assertThat(batch.keys().get(0).address()).isEqualTo(expected);
        assertThat(batch.publicEntries().get(0).derivationIndex()).isZero();
    }

    @Test
    @DisplayName("TRC-20 produces valid, deterministic, distinct TRON addresses")
    void tronBatch() {
        Batch a = OfflineDepositPoolGenerator.generate(SEED, CryptoAsset.USDT_TRC20, 5);
        Batch b = OfflineDepositPoolGenerator.generate(SEED, CryptoAsset.USDT_TRC20, 5);

        assertThat(a.keys()).hasSize(5);
        assertThat(a.keys()).allSatisfy(k -> {
            assertThat(k.address()).startsWith("T");
            assertThat(TronKeys.isValidAddress(k.address())).as("valid: %s", k.address()).isTrue();
        });
        assertThat(a.keys().stream().map(OfflineDepositPoolGenerator.Keypair::address).distinct().count())
                .isEqualTo(5);
        assertThat(a.keys().stream().map(OfflineDepositPoolGenerator.Keypair::address).toList())
                .as("deterministic from seed")
                .isEqualTo(b.keys().stream().map(OfflineDepositPoolGenerator.Keypair::address).toList());
    }

    @Test
    @DisplayName("TRON keys are independent of the ETH keys (separate derivation label)")
    void tronKeysDifferFromEth() {
        String eth = OfflineDepositPoolGenerator.generate(SEED, CryptoAsset.ETH, 1).keys().get(0).privateKeyHex();
        String tron = OfflineDepositPoolGenerator.generate(SEED, CryptoAsset.USDT_TRC20, 1)
                .keys().get(0).privateKeyHex();
        assertThat(tron).isNotEqualTo(eth);
    }
}
