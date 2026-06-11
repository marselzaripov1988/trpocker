package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the JDK-backed ed25519 key/address helpers against the authoritative RFC 8032 §7.1 test vectors
 * (seed → public key and the deterministic signature), plus base58 address round-tripping and validation.
 */
@DisplayName("SolKeys — ed25519 keys/addresses vs RFC 8032 vectors")
class SolKeysTest {

    private static final HexFormat HEX = HexFormat.of();

    // RFC 8032 §7.1 TEST 1 (empty message).
    private static final byte[] SEED1 = HEX.parseHex(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    private static final byte[] PUB1 = HEX.parseHex(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] SIG1 = HEX.parseHex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e3"
            + "9701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    // RFC 8032 §7.1 TEST 2 (one-byte message 0x72).
    private static final byte[] SEED2 = HEX.parseHex(
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
    private static final byte[] PUB2 = HEX.parseHex(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
    private static final byte[] MSG2 = HEX.parseHex("72");
    private static final byte[] SIG2 = HEX.parseHex(
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f36"
            + "13d0f11d8c387b2eaeb4302aeeb00d291612bb0c00");

    @Test
    @DisplayName("public key derived from a seed matches RFC 8032")
    void publicKeyFromSeedMatchesRfc() {
        assertThat(SolKeys.publicKeyFromSeed(SEED1)).isEqualTo(PUB1);
        assertThat(SolKeys.publicKeyFromSeed(SEED2)).isEqualTo(PUB2);
    }

    @Test
    @DisplayName("deterministic signature matches RFC 8032 and verifies")
    void signatureMatchesRfcAndVerifies() {
        assertThat(SolKeys.sign(SEED1, new byte[0])).isEqualTo(SIG1);
        assertThat(SolKeys.sign(SEED2, MSG2)).isEqualTo(SIG2);

        assertThat(SolKeys.verify(PUB1, new byte[0], SIG1)).isTrue();
        assertThat(SolKeys.verify(PUB2, MSG2, SIG2)).isTrue();
        // Wrong message / wrong key must not verify.
        assertThat(SolKeys.verify(PUB1, MSG2, SIG1)).isFalse();
        assertThat(SolKeys.verify(PUB2, new byte[0], SIG1)).isFalse();
    }

    @Test
    @DisplayName("address is base58 of the public key and round-trips")
    void addressRoundTrips() {
        String addr = SolKeys.addressFromSeed(SEED1);
        assertThat(Base58.decode(addr)).isEqualTo(PUB1);
        assertThat(SolKeys.isValidAddress(addr)).isTrue();
    }

    @Test
    @DisplayName("address validation rejects malformed / wrong-length input")
    void rejectsBadAddresses() {
        assertThat(SolKeys.isValidAddress(null)).isFalse();
        assertThat(SolKeys.isValidAddress("")).isFalse();
        assertThat(SolKeys.isValidAddress("0OIl")).isFalse();          // invalid base58 chars
        assertThat(SolKeys.isValidAddress(Base58.encode(new byte[31]))).isFalse(); // 31 bytes
        assertThat(SolKeys.isValidAddress(Base58.encode(new byte[32]))).isTrue();  // 32 bytes
    }
}
