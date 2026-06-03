package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bitcoin Taproot (P2TR) address derivation")
class TaprootKeysTest {

    @Test
    @DisplayName("private key = 1 yields the canonical key-path Taproot address")
    void canonicalVector() {
        // Cross-checked against an independent BIP-341 implementation (lift_x + TapTweak + bech32m).
        assertThat(TaprootKeys.p2trAddress(BigInteger.ONE))
                .isEqualTo("bc1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5sspknck9");
        assertThat(TaprootKeys.isValidAddress(
                "bc1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5sspknck9")).isTrue();
    }

    @Test
    @DisplayName("derived addresses are well-formed P2TR addresses (bc1p…, 62 chars)")
    void derivedAddressesAreValid() {
        for (int i = 0; i < 20; i++) {
            BigInteger priv = EthKeys.derivePrivateKey("tr-seed".getBytes(StandardCharsets.UTF_8), "btc/" + i);
            String address = TaprootKeys.p2trAddress(priv);
            assertThat(address).startsWith("bc1p").hasSize(62);
            assertThat(TaprootKeys.isValidAddress(address)).as("valid: %s", address).isTrue();
        }
    }

    @Test
    @DisplayName("a v0 (bc1q) address or garbage is not a valid Taproot address")
    void rejectsNonTaproot() {
        assertThat(TaprootKeys.isValidAddress("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")).isFalse();
        assertThat(TaprootKeys.isValidAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")).isFalse();
        assertThat(TaprootKeys.isValidAddress(null)).isFalse();
        assertThat(TaprootKeys.isValidAddress("bc1pnotrealnotrealnotreal")).isFalse();
    }
}
