package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Base58 (Bitcoin/TRON alphabet) encode/decode in pure Java — needed for TRON address (Base58Check) without
 * any external dependency. Leading zero bytes map to leading '1' characters, per the standard.
 */
public final class Base58 {

    // Bitcoin/TRON Base58 alphabet — 58 chars, excludes 0 O I l.
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(58);

    private Base58() {
    }

    public static String encode(byte[] input) {
        if (input.length == 0) {
            return "";
        }
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        BigInteger num = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();
        while (num.signum() > 0) {
            BigInteger[] divRem = num.divideAndRemainder(BASE);
            sb.append(ALPHABET.charAt(divRem[1].intValue()));
            num = divRem[0];
        }
        for (int i = 0; i < zeros; i++) {
            sb.append(ALPHABET.charAt(0));
        }
        return sb.reverse().toString();
    }

    public static byte[] decode(String input) {
        if (input.isEmpty()) {
            return new byte[0];
        }
        BigInteger num = BigInteger.ZERO;
        for (int i = 0; i < input.length(); i++) {
            int digit = ALPHABET.indexOf(input.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base58 character: " + input.charAt(i));
            }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        byte[] raw = num.toByteArray();
        // BigInteger may prepend a 0x00 sign byte; drop it.
        if (raw.length > 1 && raw[0] == 0) {
            raw = Arrays.copyOfRange(raw, 1, raw.length);
        }
        int zeros = 0;
        while (zeros < input.length() && input.charAt(zeros) == ALPHABET.charAt(0)) {
            zeros++;
        }
        byte[] out = new byte[zeros + raw.length];
        System.arraycopy(raw, 0, out, zeros, raw.length);
        return out;
    }

    /** Base58Check: append the first 4 bytes of double-SHA-256 of the payload, then Base58-encode. Used by
     *  both Bitcoin (version + HASH160) and TRON (0x41 + account hash). */
    public static String encodeChecked(byte[] payload) {
        byte[] checksum = Arrays.copyOf(doubleSha256(payload), 4);
        byte[] full = new byte[payload.length + 4];
        System.arraycopy(payload, 0, full, 0, payload.length);
        System.arraycopy(checksum, 0, full, payload.length, 4);
        return encode(full);
    }

    /** Decode a Base58Check string and verify its checksum; returns the payload (without the 4-byte
     *  checksum) or {@code null} if the string is malformed or the checksum does not match. */
    public static byte[] verifyChecked(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = decode(input);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length < 5) {
            return null;
        }
        byte[] payload = Arrays.copyOfRange(decoded, 0, decoded.length - 4);
        byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
        byte[] expected = Arrays.copyOf(doubleSha256(payload), 4);
        return Arrays.equals(checksum, expected) ? payload : null;
    }

    private static byte[] doubleSha256(byte[] input) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return sha.digest(sha.digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
