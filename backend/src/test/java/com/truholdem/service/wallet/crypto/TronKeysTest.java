package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TRON (TRC-20) address derivation")
class TronKeysTest {

    @Test
    @DisplayName("private key = 1 yields the canonical TRON address")
    void canonicalVector() {
        // secp256k1 privkey 1 → ETH 0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf → TRON Base58Check below
        // (cross-checked against an independent Base58Check implementation).
        String address = TronKeys.addressFromPrivateKey(BigInteger.ONE);
        assertThat(address).isEqualTo("TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC");
    }

    @Test
    @DisplayName("a real mainnet TRON address validates (alphabet + checksum cross-check)")
    void acceptsRealMainnetAddress() {
        // The well-known USDT (TRC-20) contract address.
        assertThat(TronKeys.isValidAddress("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")).isTrue();
    }

    @Test
    @DisplayName("derived addresses are well-formed TRON addresses")
    void derivedAddressesAreValid() {
        for (int i = 0; i < 20; i++) {
            BigInteger priv = EthKeys.derivePrivateKey("tron-seed".getBytes(StandardCharsets.UTF_8), "tron/" + i);
            String address = TronKeys.addressFromPrivateKey(priv);
            assertThat(address).startsWith("T");
            assertThat(TronKeys.isValidAddress(address)).as("valid: %s", address).isTrue();
        }
    }

    @Test
    @DisplayName("a flipped checksum / wrong prefix / garbage is rejected")
    void rejectsInvalid() {
        String valid = TronKeys.addressFromPrivateKey(BigInteger.valueOf(42));
        // flip one character → checksum no longer matches
        char[] c = valid.toCharArray();
        c[c.length - 1] = c[c.length - 1] == 'a' ? 'b' : 'a';
        String tampered = new String(c);

        assertThat(TronKeys.isValidAddress(tampered)).isFalse();
        assertThat(TronKeys.isValidAddress(null)).isFalse();
        assertThat(TronKeys.isValidAddress("")).isFalse();
        assertThat(TronKeys.isValidAddress("0xabc")).isFalse();
        assertThat(TronKeys.isValidAddress("not base58 0OIl")).isFalse();
    }

    @Test
    @DisplayName("Base58 round-trips arbitrary bytes incl. leading zeros")
    void base58RoundTrip() {
        byte[][] samples = {
                {0x41, 0x10, 0x20, 0x30},
                {0x00, 0x00, 0x01, 0x02, 0x03},
                {(byte) 0xff, (byte) 0xfe, (byte) 0xfd}
        };
        for (byte[] s : samples) {
            assertThat(Base58.decode(Base58.encode(s))).isEqualTo(s);
        }
    }
}
