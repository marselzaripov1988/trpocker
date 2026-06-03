package com.truholdem.tools.signer;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

/**
 * Minimal RLP (Recursive Length Prefix) encoder for Ethereum transaction serialization. Part of the offline
 * signer tooling — lives in test sources so signing/serialization code never ships in the server jar.
 */
public final class Rlp {

    private Rlp() {
    }

    /** RLP-encode a byte string. */
    public static byte[] encodeBytes(byte[] value) {
        if (value.length == 1 && (value[0] & 0xff) < 0x80) {
            return value;
        }
        return concat(encodeLength(value.length, 0x80), value);
    }

    /** RLP-encode a non-negative integer as a minimal-length byte string (zero → empty string). */
    public static byte[] encodeBigInteger(BigInteger value) {
        return encodeBytes(toMinimalBytes(value));
    }

    /** RLP-encode a list whose items are already RLP-encoded. */
    public static byte[] encodeList(byte[]... encodedItems) {
        byte[] payload = concat(encodedItems);
        return concat(encodeLength(payload.length, 0xc0), payload);
    }

    /** A non-negative integer as a minimal big-endian byte array (no leading zero; zero → empty). */
    public static byte[] toMinimalBytes(BigInteger value) {
        if (value.signum() == 0) {
            return new byte[0];
        }
        byte[] b = value.toByteArray();
        if (b[0] == 0) {
            byte[] trimmed = new byte[b.length - 1];
            System.arraycopy(b, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return b;
    }

    private static byte[] encodeLength(int length, int offset) {
        if (length < 56) {
            return new byte[] { (byte) (offset + length) };
        }
        byte[] lenBytes = toMinimalBytes(BigInteger.valueOf(length));
        byte[] out = new byte[1 + lenBytes.length];
        out[0] = (byte) (offset + 55 + lenBytes.length);
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.length);
        return out;
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.writeBytes(a);
        }
        return out.toByteArray();
    }
}
