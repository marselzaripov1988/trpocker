package com.truholdem.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.model.CryptoAsset;
import com.truholdem.service.wallet.crypto.EthKeys;
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
}
