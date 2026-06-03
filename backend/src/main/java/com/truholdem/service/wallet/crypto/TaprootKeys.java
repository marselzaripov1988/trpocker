package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bitcoin Taproot (P2TR, {@code bc1p…}) key-path-only address derivation in pure Java (BIP-341), reusing the
 * secp256k1 primitives in {@link EthKeys}. Steps: take the x-only internal key from {@code d·G}, lift it to
 * the even-Y point P, compute the tweak {@code t = tagged_hash("TapTweak", P_x)}, the output key
 * {@code Q = P + t·G}, then bech32m-encode {@code Q_x} as witness version 1. No script tree.
 *
 * <p><b>Demo-grade key handling</b> (same caveat as {@link EthKeys}): production self-custody keeps private
 * keys offline / in an HSM and imports only watch-only public addresses.
 */
public final class TaprootKeys {

    private static final String MAINNET_HRP = "bc";
    private static final int WITNESS_V1 = 1;

    private TaprootKeys() {
    }

    /** Taproot key-path-only P2TR ({@code bc1p…}) address for a secp256k1 private key in [1, n-1]. */
    public static String p2trAddress(BigInteger priv) {
        BigInteger[] g = { EthKeys.GX, EthKeys.GY };
        BigInteger[] internalPoint = EthKeys.mul(priv.mod(EthKeys.N), g);
        byte[] internalXOnly = EthKeys.to32(internalPoint[0]);

        BigInteger[] p = liftX(internalXOnly); // internal key lifted to even Y
        BigInteger tweak = new BigInteger(1, taggedHash("TapTweak", internalXOnly));
        if (tweak.compareTo(EthKeys.N) >= 0) {
            throw new IllegalStateException("TapTweak out of range"); // negligible probability
        }
        BigInteger[] output = EthKeys.add(p, EthKeys.mul(tweak, g)); // Q = P + t*G
        return Bech32.encodeSegwit(MAINNET_HRP, WITNESS_V1, EthKeys.to32(output[0]));
    }

    /** True iff {@code address} is a well-formed mainnet P2TR ({@code bc1p…}) address. */
    public static boolean isValidAddress(String address) {
        return Bech32.decodeP2tr(MAINNET_HRP, address) != null;
    }

    /** Lift an x-only coordinate to the secp256k1 point with even Y (BIP-340 lift_x). */
    private static BigInteger[] liftX(byte[] xOnly) {
        BigInteger p = EthKeys.P;
        BigInteger x = new BigInteger(1, xOnly);
        if (x.signum() <= 0 || x.compareTo(p) >= 0) {
            throw new IllegalArgumentException("x out of field range");
        }
        BigInteger c = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p);
        BigInteger y = c.modPow(p.add(BigInteger.ONE).shiftRight(2), p); // sqrt: c^((p+1)/4) mod p
        if (!y.multiply(y).mod(p).equals(c)) {
            throw new IllegalArgumentException("x is not on the curve");
        }
        if (y.testBit(0)) {
            y = p.subtract(y); // choose the even-Y root
        }
        return new BigInteger[] { x, y };
    }

    private static byte[] taggedHash(String tag, byte[] message) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] tagHash = sha.digest(tag.getBytes(StandardCharsets.UTF_8));
            sha.reset();
            sha.update(tagHash);
            sha.update(tagHash);
            sha.update(message);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
