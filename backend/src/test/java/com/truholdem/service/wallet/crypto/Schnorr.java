package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * BIP-340 Schnorr signatures over secp256k1 (sign + verify), pure Java, reusing {@link EthKeys}' curve math.
 * Used for Bitcoin Taproot key-path spends. Test-sources only — signing touches private keys and must never
 * ship in the server jar.
 */
public final class Schnorr {

    private static final BigInteger P = EthKeys.P;
    private static final BigInteger N = EthKeys.N;
    private static final BigInteger[] G = { EthKeys.GX, EthKeys.GY };

    private Schnorr() {
    }

    /** BIP-340 64-byte signature over a 32-byte message; {@code auxRand} is 32 bytes (zeros = deterministic). */
    public static byte[] sign(byte[] msg, BigInteger privateKey, byte[] auxRand) {
        BigInteger[] pub = EthKeys.mul(privateKey, G);
        BigInteger d = pub[1].testBit(0) ? N.subtract(privateKey) : privateKey; // even-Y secret
        byte[] px = EthKeys.to32(pub[0]);

        byte[] aux = taggedHash("BIP0340/aux", auxRand);
        byte[] dBytes = EthKeys.to32(d);
        byte[] t = new byte[32];
        for (int i = 0; i < 32; i++) {
            t[i] = (byte) (dBytes[i] ^ aux[i]);
        }
        BigInteger k0 = new BigInteger(1, taggedHash("BIP0340/nonce", concat(t, px, msg))).mod(N);
        if (k0.signum() == 0) {
            throw new IllegalStateException("Schnorr nonce k0 == 0");
        }
        BigInteger[] r = EthKeys.mul(k0, G);
        BigInteger k = r[1].testBit(0) ? N.subtract(k0) : k0; // even-Y nonce
        byte[] rx = EthKeys.to32(r[0]);
        BigInteger e = new BigInteger(1, taggedHash("BIP0340/challenge", concat(rx, px, msg))).mod(N);
        return concat(rx, EthKeys.to32(k.add(e.multiply(d)).mod(N)));
    }

    /** BIP-340 verify: a 64-byte signature against a 32-byte x-only public key over a 32-byte message. */
    public static boolean verify(byte[] msg, byte[] pubKeyX, byte[] sig) {
        if (sig.length != 64 || pubKeyX.length != 32) {
            return false;
        }
        BigInteger[] pub = liftX(new BigInteger(1, pubKeyX));
        if (pub == null) {
            return false;
        }
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(sig, 32, 64));
        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) {
            return false;
        }
        BigInteger e = new BigInteger(1, taggedHash("BIP0340/challenge",
                concat(EthKeys.to32(r), pubKeyX, msg))).mod(N);
        BigInteger[] bigR = EthKeys.add(EthKeys.mul(s, G), EthKeys.mul(N.subtract(e), pub));
        return bigR != null && !bigR[1].testBit(0) && bigR[0].equals(r);
    }

    /** Lift an x-only coordinate to the even-Y secp256k1 point (BIP-340). */
    private static BigInteger[] liftX(BigInteger x) {
        if (x.signum() <= 0 || x.compareTo(P) >= 0) {
            return null;
        }
        BigInteger c = x.modPow(BigInteger.valueOf(3), P).add(BigInteger.valueOf(7)).mod(P);
        BigInteger y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!y.multiply(y).mod(P).equals(c)) {
            return null;
        }
        if (y.testBit(0)) {
            y = P.subtract(y);
        }
        return new BigInteger[] { x, y };
    }

    static byte[] taggedHash(String tag, byte[] msg) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] th = sha.digest(tag.getBytes(StandardCharsets.UTF_8));
            sha.reset();
            sha.update(th);
            sha.update(th);
            sha.update(msg);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] concat(byte[]... arrays) {
        int len = 0;
        for (byte[] a : arrays) {
            len += a.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }
}
