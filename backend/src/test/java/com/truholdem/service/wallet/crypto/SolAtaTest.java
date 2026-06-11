package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the ed25519 on-curve check (against known valid points + freshly generated keys) and the structural
 * invariants of ATA derivation (deterministic, 32 bytes, off-curve, valid base58). The exact (owner, mint) →
 * ATA value is cross-checked end-to-end against {@code solana-test-validator} in the verify slice.
 */
@DisplayName("SolAta — ed25519 on-curve check + ATA derivation")
class SolAtaTest {

    private static final HexFormat HEX = HexFormat.of();
    // Valid ed25519 public keys (RFC 8032 §7.1) — must be on the curve.
    private static final byte[] PUB1 = HEX.parseHex(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] PUB2 = HEX.parseHex(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");

    @Test
    @DisplayName("valid ed25519 public keys are on-curve")
    void validKeysAreOnCurve() {
        assertThat(SolAta.isOnCurve(PUB1)).isTrue();
        assertThat(SolAta.isOnCurve(PUB2)).isTrue();
        // A spread of freshly generated keys are all valid curve points.
        for (int i = 1; i <= 8; i++) {
            byte[] seed = new byte[32];
            seed[0] = (byte) i;
            assertThat(SolAta.isOnCurve(SolKeys.publicKeyFromSeed(seed)))
                    .as("generated key %d on-curve", i).isTrue();
        }
    }

    @Test
    @DisplayName("on-curve check rejects malformed length")
    void rejectsBadLength() {
        assertThat(SolAta.isOnCurve(new byte[31])).isFalse();
        assertThat(SolAta.isOnCurve(null)).isFalse();
    }

    @Test
    @DisplayName("ATA derivation is deterministic, 32 bytes, off-curve, valid base58")
    void ataDerivationInvariants() {
        byte[] owner = SolKeys.publicKeyFromSeed(seed(7));
        byte[] mint = Base58.decode("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"); // USDT SPL mint

        byte[] ata1 = SolAta.deriveAtaBytes(owner, mint);
        byte[] ata2 = SolAta.deriveAtaBytes(owner, mint);

        assertThat(ata1).hasSize(32).isEqualTo(ata2);               // deterministic
        assertThat(SolAta.isOnCurve(ata1)).isFalse();               // a PDA is off-curve (no private key)
        String ataAddr = SolAta.deriveAta(SolKeys.address(owner), "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB");
        assertThat(SolKeys.isValidAddress(ataAddr)).isTrue();
        assertThat(Base58.decode(ataAddr)).isEqualTo(ata1);
    }

    @Test
    @DisplayName("program ids decode to 32 bytes")
    void programIdsAre32Bytes() {
        assertThat(Base58.decode(SolAta.TOKEN_PROGRAM_ID)).hasSize(32);
        assertThat(Base58.decode(SolAta.ASSOCIATED_TOKEN_PROGRAM_ID)).hasSize(32);
    }

    private static byte[] seed(int n) {
        byte[] s = new byte[32];
        s[0] = (byte) n;
        return s;
    }
}
