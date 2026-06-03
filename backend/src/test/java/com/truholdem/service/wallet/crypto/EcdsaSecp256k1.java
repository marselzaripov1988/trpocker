package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ECDSA over secp256k1 with RFC 6979 deterministic nonces (HMAC-SHA256) + low-s (EIP-2) — pure Java, reusing
 * the curve math in {@link EthKeys}. Part of the OFFLINE signer tooling: lives in test sources so the
 * signing capability (which touches private keys) never ships inside the online server jar. Placed in the
 * {@code crypto} package so it can reuse {@link EthKeys}'s package-private secp256k1 primitives.
 */
public final class EcdsaSecp256k1 {

    private static final BigInteger N = EthKeys.N;
    private static final BigInteger HALF_N = N.shiftRight(1);

    private EcdsaSecp256k1() {
    }

    /** An ECDSA signature with the public-key recovery id (0..3). */
    public record Signature(BigInteger r, BigInteger s, int recId) {
    }

    /** Sign a 32-byte message hash with the private key (deterministic; low-s). */
    public static Signature sign(byte[] messageHash, BigInteger privateKey) {
        BigInteger z = new BigInteger(1, messageHash);
        BigInteger k = deterministicK(privateKey, messageHash);
        BigInteger[] point = EthKeys.mul(k, new BigInteger[] { EthKeys.GX, EthKeys.GY });
        BigInteger r = point[0].mod(N);
        if (r.signum() == 0) {
            throw new IllegalStateException("ECDSA r == 0");
        }
        BigInteger s = k.modInverse(N).multiply(z.add(r.multiply(privateKey))).mod(N);
        if (s.signum() == 0) {
            throw new IllegalStateException("ECDSA s == 0");
        }
        int recId = point[1].testBit(0) ? 1 : 0;
        if (point[0].compareTo(N) >= 0) {
            recId += 2;
        }
        if (s.compareTo(HALF_N) > 0) { // enforce low-s (EIP-2); flips the recovery parity
            s = N.subtract(s);
            recId ^= 1;
        }
        return new Signature(r, s, recId);
    }

    // RFC 6979 §3.2 with HMAC-SHA256, qlen = 256 (so bits2int of a 32-byte value is the value itself).
    private static BigInteger deterministicK(BigInteger privateKey, byte[] hash) {
        byte[] xOctets = EthKeys.to32(privateKey);
        byte[] hOctets = bits2octets(hash);
        byte[] v = new byte[32];
        Arrays.fill(v, (byte) 0x01);
        byte[] k = new byte[32]; // all zeros
        k = hmac(k, concat(v, new byte[] { 0x00 }, xOctets, hOctets));
        v = hmac(k, v);
        k = hmac(k, concat(v, new byte[] { 0x01 }, xOctets, hOctets));
        v = hmac(k, v);
        while (true) {
            v = hmac(k, v);
            BigInteger candidate = new BigInteger(1, v);
            if (candidate.signum() > 0 && candidate.compareTo(N) < 0) {
                return candidate;
            }
            k = hmac(k, concat(v, new byte[] { 0x00 }));
            v = hmac(k, v);
        }
    }

    private static byte[] bits2octets(byte[] hash) {
        BigInteger z1 = new BigInteger(1, hash);
        BigInteger z2 = z1.compareTo(N) >= 0 ? z1.subtract(N) : z1;
        return EthKeys.to32(z2);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
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
