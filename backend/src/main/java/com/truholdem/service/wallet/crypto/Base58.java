package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
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
}
