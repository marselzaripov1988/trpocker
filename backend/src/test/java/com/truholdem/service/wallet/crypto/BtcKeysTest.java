package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bitcoin P2PKH address derivation")
class BtcKeysTest {

    @Test
    @DisplayName("private key = 1 yields the canonical compressed P2PKH address")
    void canonicalVector() {
        // Cross-checked against an independent Base58Check + HASH160 implementation.
        assertThat(BtcKeys.p2pkhAddress(BigInteger.ONE)).isEqualTo("1BgGZ9tcN4rm9KBzDn7KprQz87SZ26SAMH");
    }

    @Test
    @DisplayName("a real mainnet P2PKH address validates (Base58Check + version cross-check)")
    void acceptsRealMainnetAddress() {
        // The Bitcoin genesis-block coinbase address.
        assertThat(BtcKeys.isValidP2pkhAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")).isTrue();
    }

    @Test
    @DisplayName("derived addresses are well-formed P2PKH addresses")
    void derivedAddressesAreValid() {
        for (int i = 0; i < 20; i++) {
            BigInteger priv = EthKeys.derivePrivateKey("btc-seed".getBytes(StandardCharsets.UTF_8), "btc/" + i);
            String address = BtcKeys.p2pkhAddress(priv);
            assertThat(address).startsWith("1");
            assertThat(BtcKeys.isValidP2pkhAddress(address)).as("valid: %s", address).isTrue();
        }
    }

    @Test
    @DisplayName("compressed pubkey is 33 bytes with a 0x02/0x03 parity prefix")
    void compressedPubkeyShape() {
        byte[] compressed = BtcKeys.compressedPublicKey(BigInteger.valueOf(12345));
        assertThat(compressed).hasSize(33);
        assertThat(compressed[0]).isIn((byte) 0x02, (byte) 0x03);
    }

    @Test
    @DisplayName("garbage / wrong-checksum / non-P2PKH is rejected")
    void rejectsInvalid() {
        String valid = BtcKeys.p2pkhAddress(BigInteger.valueOf(42));
        char[] c = valid.toCharArray();
        c[c.length - 1] = c[c.length - 1] == 'a' ? 'b' : 'a';

        assertThat(BtcKeys.isValidP2pkhAddress(new String(c))).isFalse();
        assertThat(BtcKeys.isValidP2pkhAddress(null)).isFalse();
        assertThat(BtcKeys.isValidP2pkhAddress("")).isFalse();
        assertThat(BtcKeys.isValidP2pkhAddress("0xabc")).isFalse();
        // A valid TRON address must not pass as Bitcoin.
        assertThat(BtcKeys.isValidP2pkhAddress("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")).isFalse();
    }
}
