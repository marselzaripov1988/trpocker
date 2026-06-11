package com.truholdem.service.wallet.sol;

import java.io.ByteArrayOutputStream;

/**
 * Solana "compact-u16" (shortvec) length encoding: a u16 written as 1–3 bytes, 7 bits per byte, high bit = more
 * bytes follow. Used to prefix every array in the transaction wire format (account keys, instructions, the
 * instruction's account indices + data, and the signatures array).
 */
final class SolShortVec {

    private SolShortVec() {
    }

    /** Encode a non-negative length (≤ 0xffff) as compact-u16. */
    static byte[] encodeLength(int len) {
        if (len < 0 || len > 0xffff) {
            throw new IllegalArgumentException("compact-u16 length out of range: " + len);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(3);
        int rem = len;
        while (true) {
            int elem = rem & 0x7f;
            rem >>>= 7;
            if (rem == 0) {
                out.write(elem);
                break;
            }
            out.write(elem | 0x80);
        }
        return out.toByteArray();
    }
}
