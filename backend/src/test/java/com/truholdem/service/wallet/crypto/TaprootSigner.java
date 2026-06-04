package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;

/**
 * Bitcoin Taproot (P2TR) key-path-only signing (BIP-341): tweak the internal key and Schnorr-sign the
 * BIP-341 sighash. The sighash itself (which commits to all spent prevout amounts + scriptPubKeys) is built
 * online from the PSBT/node; this offline tool only applies the tweak and signs. Pure Java, test-sources only.
 */
public final class TaprootSigner {

    private static final BigInteger N = EthKeys.N;
    private static final BigInteger[] G = { EthKeys.GX, EthKeys.GY };

    private TaprootSigner() {
    }

    /** BIP-341 key-path tweaked private key (no script tree): {@code (d_even + tagged("TapTweak", P_x)) mod n}. */
    public static BigInteger tweakedPrivateKey(BigInteger internalPrivateKey) {
        BigInteger[] p = EthKeys.mul(internalPrivateKey, G);
        BigInteger d = p[1].testBit(0) ? N.subtract(internalPrivateKey) : internalPrivateKey;
        BigInteger tweak = new BigInteger(1, Schnorr.taggedHash("TapTweak", EthKeys.to32(p[0])));
        if (tweak.compareTo(N) >= 0) {
            throw new IllegalStateException("TapTweak out of range");
        }
        return d.add(tweak).mod(N);
    }

    /** The x-only Taproot output key for the internal key — equals the {@code bc1p…} witness program. */
    public static byte[] outputKeyX(BigInteger internalPrivateKey) {
        return EthKeys.to32(EthKeys.mul(tweakedPrivateKey(internalPrivateKey), G)[0]);
    }

    /** Sign a Taproot key-path spend: BIP-340 Schnorr over the BIP-341 sighash with the tweaked key. */
    public static byte[] signKeyPath(BigInteger internalPrivateKey, byte[] sighash, byte[] auxRand) {
        return Schnorr.sign(sighash, tweakedPrivateKey(internalPrivateKey), auxRand);
    }
}
