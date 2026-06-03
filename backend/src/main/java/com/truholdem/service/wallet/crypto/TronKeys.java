package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * TRON (TRC-20) address derivation in pure Java. A TRON account uses the SAME secp256k1 keypair and
 * Keccak-256 account hash as Ethereum (reused from {@link EthKeys}); only the textual encoding differs:
 * prepend the {@code 0x41} mainnet prefix to the 20 address bytes and Base58Check-encode (payload + first 4
 * bytes of double-SHA-256). Result is a {@code T…} address.
 *
 * <p><b>Demo-grade key handling</b> (same caveat as {@link EthKeys}): production self-custody keeps private
 * keys offline / in an HSM and imports only watch-only public addresses.
 */
public final class TronKeys {

    private static final byte MAINNET_PREFIX = 0x41;

    private TronKeys() {
    }

    /** TRON Base58Check ({@code T…}) address for a secp256k1 private key in [1, n-1]. */
    public static String addressFromPrivateKey(BigInteger priv) {
        byte[] addr20 = EthKeys.addressBytesFromPrivateKey(priv);
        byte[] payload = new byte[21];
        payload[0] = MAINNET_PREFIX;
        System.arraycopy(addr20, 0, payload, 1, 20);
        return base58Check(payload);
    }

    /** True iff {@code address} is a well-formed TRON address: Base58Check, 0x41 prefix, valid checksum. */
    public static boolean isValidAddress(String address) {
        if (address == null || address.isEmpty() || address.charAt(0) != 'T') {
            return false;
        }
        byte[] decoded;
        try {
            decoded = Base58.decode(address);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (decoded.length != 25 || (decoded[0] & 0xff) != (MAINNET_PREFIX & 0xff)) {
            return false;
        }
        byte[] payload = Arrays.copyOfRange(decoded, 0, 21);
        byte[] checksum = Arrays.copyOfRange(decoded, 21, 25);
        byte[] expected = Arrays.copyOfRange(doubleSha256(payload), 0, 4);
        return Arrays.equals(checksum, expected);
    }

    private static String base58Check(byte[] payload) {
        byte[] checksum = Arrays.copyOfRange(doubleSha256(payload), 0, 4);
        byte[] full = new byte[payload.length + 4];
        System.arraycopy(payload, 0, full, 0, payload.length);
        System.arraycopy(checksum, 0, full, payload.length, 4);
        return Base58.encode(full);
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
