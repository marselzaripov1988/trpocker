package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BIP-340 Schnorr + BIP-341 Taproot key-path signing, verified against the official BIP-340 test vector and a
 * Taproot tweak vector computed by an independent reference.
 */
@DisplayName("BIP-340 Schnorr + Taproot key-path signer")
class BtcTaprootSignerTest {

    private static final byte[] ZERO32 = new byte[32];

    private static byte[] hex(String h) {
        return HexFormat.of().parseHex(h);
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    @Test
    @DisplayName("official BIP-340 vector (secret key = 3)")
    void bip340OfficialVector() {
        byte[] sig = Schnorr.sign(ZERO32, BigInteger.valueOf(3), ZERO32);
        assertThat(hex(sig)).isEqualTo("e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca8215"
                + "25f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0");

        byte[] pubX = EthKeys.to32(EthKeys.mul(BigInteger.valueOf(3),
                new BigInteger[] { EthKeys.GX, EthKeys.GY })[0]);
        assertThat(hex(pubX).toUpperCase())
                .isEqualTo("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9");
        assertThat(Schnorr.verify(ZERO32, pubX, sig)).isTrue();

        sig[10] ^= 0x01; // tamper
        assertThat(Schnorr.verify(ZERO32, pubX, sig)).isFalse();
    }

    @Test
    @DisplayName("Taproot key tweak: output key for internal key = 1")
    void taprootOutputKey() {
        assertThat(hex(TaprootSigner.outputKeyX(BigInteger.ONE)))
                .isEqualTo("da4710964f7852695de2da025290e24af6d8c281de5a0b902b7135fd9fd74d21");
    }

    @Test
    @DisplayName("Taproot key-path signature reproduces the reference and verifies against the output key")
    void taprootKeyPathSign() {
        byte[] msg = hex("3fa18779a7fe45b6a5008bb4d20532b60eb4332ef2af381684323767b4f966d2");
        byte[] sig = TaprootSigner.signKeyPath(BigInteger.ONE, msg, ZERO32);

        assertThat(hex(sig)).isEqualTo("b21dddb0a44d481487e594b2f737c72d21c99813ea5d044872d256640a930e95"
                + "3bc13167ee8ec509d3cb7be6b5470fa936397009e518297c1301e6ae5d15a65b");
        assertThat(Schnorr.verify(msg, TaprootSigner.outputKeyX(BigInteger.ONE), sig)).isTrue();
    }
}
