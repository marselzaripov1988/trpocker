package com.truholdem.service.wallet.eth;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Minimal Ethereum ABI / hex helpers for the online withdrawal coordinator (pure Java, no web3j). Builds the
 * calldata for ERC-20 {@code transfer(address,uint256)} and {@code balanceOf(address)} and converts hex
 * to/from bytes. This is plain encoding — it neither holds nor uses any private key.
 */
public final class EthAbi {

    /** {@code keccak256("transfer(address,uint256)")[:4]}. */
    private static final byte[] TRANSFER_SELECTOR = HexFormat.of().parseHex("a9059cbb");
    /** {@code keccak256("balanceOf(address)")[:4]}. */
    private static final byte[] BALANCE_OF_SELECTOR = HexFormat.of().parseHex("70a08231");

    private EthAbi() {
    }

    /** Calldata for ERC-20 {@code transfer(to, amount)}: selector + 32-byte-padded address + amount. */
    public static byte[] erc20TransferData(byte[] to20, BigInteger amount) {
        if (to20.length != 20) {
            throw new IllegalArgumentException("recipient must be 20 bytes, got " + to20.length);
        }
        byte[] out = new byte[4 + 32 + 32];
        System.arraycopy(TRANSFER_SELECTOR, 0, out, 0, 4);
        System.arraycopy(leftPad32(to20), 0, out, 4, 32);
        System.arraycopy(uint256(amount), 0, out, 36, 32);
        return out;
    }

    /** Calldata for ERC-20 {@code balanceOf(owner)}. */
    public static byte[] balanceOfData(byte[] owner20) {
        if (owner20.length != 20) {
            throw new IllegalArgumentException("owner must be 20 bytes, got " + owner20.length);
        }
        byte[] out = new byte[4 + 32];
        System.arraycopy(BALANCE_OF_SELECTOR, 0, out, 0, 4);
        System.arraycopy(leftPad32(owner20), 0, out, 4, 32);
        return out;
    }

    /** Big-endian 32-byte unsigned encoding of a non-negative integer (ABI {@code uint256}). */
    public static byte[] uint256(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("uint256 cannot be negative");
        }
        byte[] raw = value.toByteArray(); // may have a leading sign byte or be < 32 bytes
        if (raw.length > 33 || (raw.length == 33 && raw[0] != 0)) {
            throw new IllegalArgumentException("value does not fit in uint256");
        }
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] leftPad32(byte[] data) {
        byte[] out = new byte[32];
        System.arraycopy(data, 0, out, 32 - data.length, data.length);
        return out;
    }

    /** Decode a 0x-prefixed or bare hex string to bytes. */
    public static byte[] hexToBytes(String hex) {
        String s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (s.length() % 2 != 0) {
            s = "0" + s; // tolerate odd-length quantities (e.g. RPC "0x1")
        }
        return HexFormat.of().parseHex(s);
    }

    /** 0x-prefixed lowercase hex of the bytes. */
    public static String toHex(byte[] data) {
        return "0x" + HexFormat.of().formatHex(data);
    }

    /** Parse a hex quantity (0x-prefixed, as returned by JSON-RPC) into a BigInteger. */
    public static BigInteger hexToBigInteger(String hexQuantity) {
        String s = hexQuantity.startsWith("0x") || hexQuantity.startsWith("0X")
                ? hexQuantity.substring(2) : hexQuantity;
        return s.isEmpty() ? BigInteger.ZERO : new BigInteger(s, 16);
    }

    /** Minimal 0x-prefixed hex quantity for JSON-RPC params (no leading zeros; zero → "0x0"). */
    public static String toQuantity(BigInteger value) {
        return "0x" + (value.signum() == 0 ? "0" : value.toString(16));
    }

    /** Decode a 20-byte address (0x-prefixed) from a withdrawal/config string. */
    public static byte[] address20(String address) {
        byte[] bytes = hexToBytes(address);
        if (bytes.length != 20) {
            throw new IllegalArgumentException("address must be 20 bytes, got " + bytes.length + ": " + address);
        }
        return bytes;
    }

    /** True if {@code a} and {@code b} are equal byte arrays (constant work, length-aware). */
    public static boolean bytesEqual(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
