package com.truholdem.service.wallet.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated encryption for KYC media at rest — AES-256-GCM in pure JDK (no dependency). The on-disk blob
 * is {@code [12-byte random IV][ciphertext + 16-byte GCM tag]}; GCM detects tampering on decrypt. The key is
 * supplied by config (base64) and never stored with the data.
 */
public final class KycCrypto {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private KycCrypto() {
    }

    public static byte[] encrypt(byte[] plaintext, byte[] key) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, out, IV_LENGTH, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("KYC encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] blob, byte[] key) {
        if (blob.length < IV_LENGTH) {
            throw new IllegalArgumentException("Ciphertext too short");
        }
        try {
            byte[] iv = Arrays.copyOfRange(blob, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(blob, IV_LENGTH, blob.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("KYC decryption failed (wrong key or tampered data)", e);
        }
    }
}
