package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Ethereum key/address generation in pure Java — proof that you can derive keys/addresses WITHOUT a payment
 * provider: secp256k1 scalar multiply (BigInteger affine math) for private→public key, Keccak-256 of the
 * uncompressed public key for the address, and EIP-55 checksum casing.
 *
 * <p><b>Demo-grade — do not use the key handling in production.</b> Real self-custody must use a vetted
 * crypto library (web3j/BouncyCastle), keep private keys in an HSM, and derive deposit addresses watch-only
 * from a BIP-32 xpub (so the web tier never holds private keys). Here {@link #derivePrivateKey} is a simple
 * HMAC-SHA512 KDF (not BIP-44) just to show deterministic per-user address derivation.
 */
public final class EthKeys {

    private static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    private static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger GX = new BigInteger(
            "79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    private static final BigInteger GY = new BigInteger(
            "483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final SecureRandom RANDOM = new SecureRandom();

    private EthKeys() {
    }

    /** The raw 20-byte account address (last 20 bytes of Keccak-256 of the uncompressed public key) for a
     *  secp256k1 private key. Shared by Ethereum (→ EIP-55 hex) and TRON (→ 0x41 prefix + Base58Check). */
    public static byte[] addressBytesFromPrivateKey(BigInteger priv) {
        BigInteger[] pub = mul(priv, new BigInteger[] { GX, GY });
        byte[] xy = new byte[64];
        System.arraycopy(to32(pub[0]), 0, xy, 0, 32);
        System.arraycopy(to32(pub[1]), 0, xy, 32, 32);
        byte[] hash = Keccak256.digest(xy);
        byte[] addr = new byte[20];
        System.arraycopy(hash, 12, addr, 0, 20);
        return addr;
    }

    /** EIP-55 checksummed 0x-address for a secp256k1 private key in [1, n-1]. */
    public static String addressFromPrivateKey(BigInteger priv) {
        return toChecksumAddress(addressBytesFromPrivateKey(priv));
    }

    /** A fresh random secp256k1 private key. */
    public static BigInteger randomPrivateKey() {
        while (true) {
            BigInteger k = new BigInteger(256, RANDOM);
            if (k.signum() > 0 && k.compareTo(N) < 0) {
                return k;
            }
        }
    }

    /** Deterministic per-label private key: HMAC-SHA512(masterSeed, label) reduced into [1, n-1]. */
    public static BigInteger derivePrivateKey(byte[] masterSeed, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(masterSeed, "HmacSHA512"));
            byte[] out = mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, out).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA512 unavailable", e);
        }
    }

    /** True iff {@code address} is a well-formed 0x-address whose EIP-55 checksum casing is exactly correct.
     *  Rejects malformed input and addresses with wrong (e.g. tampered) checksum casing. */
    public static boolean isValidChecksumAddress(String address) {
        if (address == null || address.length() != 42 || !address.startsWith("0x")) {
            return false;
        }
        String hex = address.substring(2);
        byte[] addr20 = new byte[20];
        for (int i = 0; i < 40; i++) {
            int d = Character.digit(hex.charAt(i), 16);
            if (d < 0) {
                return false;
            }
            if (i % 2 == 0) {
                addr20[i / 2] = (byte) (d << 4);
            } else {
                addr20[i / 2] |= (byte) d;
            }
        }
        return address.equals(toChecksumAddress(addr20));
    }

    /** EIP-55 checksum casing for a 20-byte address. */
    public static String toChecksumAddress(byte[] addr20) {
        String hex = toHex(addr20);
        byte[] h = Keccak256.digest(hex.getBytes(StandardCharsets.US_ASCII));
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < 40; i++) {
            char c = hex.charAt(i);
            if (c >= 'a' && c <= 'f') {
                int nibble = (i % 2 == 0) ? (h[i / 2] >> 4) & 0xf : h[i / 2] & 0xf;
                sb.append(nibble >= 8 ? Character.toUpperCase(c) : c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // --- secp256k1 affine point math (a = 0) ---

    private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
        if (p == null) {
            return q;
        }
        if (q == null) {
            return p;
        }
        BigInteger x1 = p[0], y1 = p[1], x2 = q[0], y2 = q[1];
        BigInteger lambda;
        if (x1.equals(x2)) {
            if (y1.add(y2).mod(P).signum() == 0) {
                return null; // P + (-P) = infinity
            }
            lambda = x1.multiply(x1).multiply(THREE)
                    .multiply(y1.shiftLeft(1).modInverse(P)).mod(P);
        } else {
            lambda = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(P)).mod(P);
        }
        BigInteger x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(P);
        BigInteger y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P);
        return new BigInteger[] { x3, y3 };
    }

    private static BigInteger[] mul(BigInteger k, BigInteger[] g) {
        BigInteger[] result = null;
        BigInteger[] addend = g;
        while (k.signum() > 0) {
            if (k.testBit(0)) {
                result = add(result, addend);
            }
            addend = add(addend, addend);
            k = k.shiftRight(1);
        }
        return result;
    }

    private static byte[] to32(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] r = new byte[32];
        if (b.length > 32) {
            System.arraycopy(b, b.length - 32, r, 0, 32);
        } else {
            System.arraycopy(b, 0, r, 32 - b.length, b.length);
        }
        return r;
    }

    private static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) {
            s.append(Character.forDigit((x >> 4) & 0xf, 16));
            s.append(Character.forDigit(x & 0xf, 16));
        }
        return s.toString();
    }
}
