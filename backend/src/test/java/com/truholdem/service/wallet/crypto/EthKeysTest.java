package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Pure-Java ETH key/address generation (no provider)")
class EthKeysTest {

    @Test
    @DisplayName("Keccak-256 matches the canonical empty-input vector")
    void keccakEmptyVector() {
        String hex = HexFormat.of().formatHex(Keccak256.digest(new byte[0]));
        assertThat(hex).isEqualTo("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
    }

    @Test
    @DisplayName("private key 1 derives the well-known address (secp256k1 + keccak)")
    void privateKeyOneAddress() {
        String addr = EthKeys.addressFromPrivateKey(BigInteger.ONE);
        assertThat(addr).isEqualToIgnoringCase("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf");
    }

    @Test
    @DisplayName("EIP-55 checksum casing matches the spec vector")
    void eip55Checksum() {
        byte[] addr = HexFormat.of().parseHex("fb6916095ca1df60bb79ce92ce3ea74c37c5d359");
        assertThat(EthKeys.toChecksumAddress(addr)).isEqualTo("0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359");
    }

    @Test
    @DisplayName("derivation is deterministic per label and distinct across labels")
    void deterministicDerivation() {
        byte[] seed = "master-seed-demo".getBytes();
        BigInteger a = EthKeys.derivePrivateKey(seed, "user/A");
        BigInteger a2 = EthKeys.derivePrivateKey(seed, "user/A");
        BigInteger b = EthKeys.derivePrivateKey(seed, "user/B");
        assertThat(a).isEqualTo(a2);
        assertThat(b).isNotEqualTo(a);
        assertThat(EthKeys.addressFromPrivateKey(a)).matches("0x[0-9a-fA-F]{40}");
    }
}
