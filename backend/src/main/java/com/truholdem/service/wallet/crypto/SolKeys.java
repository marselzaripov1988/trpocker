package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Solana (ed25519) key + address helpers in pure JDK — no external dependency, using {@code java.security}'s
 * built-in Ed25519 (Java 15+). A Solana address is just the Base58 of the 32-byte ed25519 public key. The
 * keypair is derived deterministically from a 32-byte seed (the offline holder re-derives the signing key by
 * seed, mirroring the BTC/ETH offline-pool model).
 *
 * <p><b>Demo-grade key handling — do not run private keys in production.</b> Real self-custody keeps the seed
 * offline (HSM / air-gapped signer) and the web tier only ever sees the public address. Verified against the
 * RFC 8032 §7.1 Ed25519 test vectors in {@code SolKeysTest}.
 */
public final class SolKeys {

    private SolKeys() {
    }

    /** Deterministic ed25519 keypair from a 32-byte seed (the ed25519 private seed). */
    public static KeyPair keypairFromSeed(byte[] seed32) {
        requireLen(seed32, 32, "seed");
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            kpg.initialize(NamedParameterSpec.ED25519, fixedRandom(seed32));
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    /** The raw 32-byte ed25519 public key (Solana address bytes): little-endian y with the x-sign in bit 255. */
    public static byte[] publicKeyBytes(PublicKey pub) {
        EdECPoint point = ((EdECPublicKey) pub).getPoint();
        byte[] le = to32LE(point.getY());
        if (point.isXOdd()) {
            le[31] |= (byte) 0x80;
        }
        return le;
    }

    /** The 32-byte public key for a seed. */
    public static byte[] publicKeyFromSeed(byte[] seed32) {
        return publicKeyBytes(keypairFromSeed(seed32).getPublic());
    }

    /** Base58 Solana address from 32 raw public-key bytes. */
    public static String address(byte[] pubkey32) {
        requireLen(pubkey32, 32, "pubkey");
        return Base58.encode(pubkey32);
    }

    /** Base58 Solana address for a seed. */
    public static String addressFromSeed(byte[] seed32) {
        return address(publicKeyFromSeed(seed32));
    }

    /** 64-byte ed25519 signature over {@code msg} by the seed's key. */
    public static byte[] sign(byte[] seed32, byte[] msg) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(keypairFromSeed(seed32).getPrivate());
            s.update(msg);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** Verify a 64-byte ed25519 signature against a 32-byte public key. */
    public static boolean verify(byte[] pubkey32, byte[] msg, byte[] sig) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKeyFromBytes(pubkey32));
            s.update(msg);
            return s.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** True iff {@code address} is a base58 string decoding to exactly 32 bytes (a Solana pubkey/address). */
    public static boolean isValidAddress(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        try {
            return Base58.decode(address).length == 32;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Rebuild a JDK Ed25519 public key from 32 raw bytes (JDK validates the point is on-curve). */
    private static PublicKey publicKeyFromBytes(byte[] pubkey32) throws GeneralSecurityException {
        requireLen(pubkey32, 32, "pubkey");
        byte[] le = pubkey32.clone();
        boolean xOdd = (le[31] & 0x80) != 0;
        le[31] &= (byte) 0x7f;
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = le[31 - i];
        }
        BigInteger y = new BigInteger(1, be);
        EdECPoint point = new EdECPoint(xOdd, y);
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
    }

    /** 32-byte little-endian encoding of a non-negative value < 2^256. */
    private static byte[] to32LE(BigInteger v) {
        byte[] src = v.toByteArray(); // big-endian, may have a sign byte / be shorter
        byte[] le = new byte[32];
        for (int i = 0; i < src.length; i++) {
            int b = src[src.length - 1 - i] & 0xff;
            if (i < 32) {
                le[i] = (byte) b;
            }
        }
        return le;
    }

    private static void requireLen(byte[] b, int len, String name) {
        if (b == null || b.length != len) {
            throw new IllegalArgumentException(name + " must be " + len + " bytes");
        }
    }

    /**
     * A SecureRandom that hands out a fixed seed so {@link KeyPairGenerator} derives a deterministic ed25519
     * keypair (Ed25519 keygen consumes exactly 32 random bytes as the private seed). Verified by the RFC 8032
     * vectors — if the JDK ever consumed randomness differently, those tests would catch it.
     */
    private static SecureRandom fixedRandom(byte[] seed) {
        return new SecureRandom() {
            private boolean served;

            @Override
            public void nextBytes(byte[] bytes) {
                if (!served && bytes.length == seed.length) {
                    System.arraycopy(seed, 0, bytes, 0, seed.length);
                    served = true;
                } else {
                    for (int i = 0; i < bytes.length; i++) {
                        bytes[i] = seed[i % seed.length];
                    }
                }
            }
        };
    }
}
