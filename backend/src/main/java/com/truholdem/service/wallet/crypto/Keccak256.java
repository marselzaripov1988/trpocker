package com.truholdem.service.wallet.crypto;

import java.util.Arrays;

/**
 * Keccak-256 (the Ethereum variant — domain suffix {@code 0x01}, NOT SHA3-256's {@code 0x06}). Pure Java,
 * no dependencies, so it works in this offline build. Verified against the canonical empty-input vector.
 *
 * <p><b>Demo-grade.</b> For production crypto use a vetted library (web3j / BouncyCastle), not a hand-rolled
 * primitive.
 */
public final class Keccak256 {

    private static final int RATE = 136; // bytes = (1600 - 512) / 8

    private static final long[] RC = {
            0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL, 0x8000000080008000L,
            0x000000000000808BL, 0x0000000080000001L, 0x8000000080008081L, 0x8000000000008009L,
            0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L, 0x8000000000008003L,
            0x8000000000008002L, 0x8000000000000080L, 0x000000000000800AL, 0x800000008000000AL,
            0x8000000080008081L, 0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };
    private static final int[] ROTC = {
            1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44
    };
    private static final int[] PILN = {
            10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1
    };

    private Keccak256() {
    }

    public static byte[] digest(byte[] input) {
        long[] st = new long[25];
        int offset = 0;
        while (input.length - offset >= RATE) {
            absorb(st, input, offset);
            keccakf(st);
            offset += RATE;
        }
        byte[] last = new byte[RATE];
        int rem = input.length - offset;
        System.arraycopy(input, offset, last, 0, rem);
        last[rem] = 0x01;               // Keccak domain suffix
        last[RATE - 1] |= (byte) 0x80;  // final bit of pad10*1
        absorb(st, last, 0);
        keccakf(st);

        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            long lane = st[i];
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (lane >>> (8 * b));
            }
        }
        Arrays.fill(st, 0L);
        return out;
    }

    private static void absorb(long[] st, byte[] data, int off) {
        for (int i = 0; i < RATE / 8; i++) {
            long lane = 0;
            for (int b = 0; b < 8; b++) {
                lane |= (long) (data[off + i * 8 + b] & 0xff) << (8 * b);
            }
            st[i] ^= lane;
        }
    }

    private static long rotl(long x, int n) {
        return (x << n) | (x >>> (64 - n));
    }

    private static void keccakf(long[] st) {
        long[] bc = new long[5];
        for (int round = 0; round < 24; round++) {
            for (int i = 0; i < 5; i++) {
                bc[i] = st[i] ^ st[i + 5] ^ st[i + 10] ^ st[i + 15] ^ st[i + 20];
            }
            for (int i = 0; i < 5; i++) {
                long t = bc[(i + 4) % 5] ^ rotl(bc[(i + 1) % 5], 1);
                for (int j = 0; j < 25; j += 5) {
                    st[j + i] ^= t;
                }
            }
            long t = st[1];
            for (int i = 0; i < 24; i++) {
                int j = PILN[i];
                long tmp = st[j];
                st[j] = rotl(t, ROTC[i]);
                t = tmp;
            }
            for (int j = 0; j < 25; j += 5) {
                for (int i = 0; i < 5; i++) {
                    bc[i] = st[j + i];
                }
                for (int i = 0; i < 5; i++) {
                    st[j + i] ^= (~bc[(i + 1) % 5]) & bc[(i + 2) % 5];
                }
            }
            st[0] ^= RC[round];
        }
    }
}
