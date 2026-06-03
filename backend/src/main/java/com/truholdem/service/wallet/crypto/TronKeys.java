package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;

/**
 * TRON (TRC-20) address derivation in pure Java. A TRON account uses the SAME secp256k1 keypair and
 * Keccak-256 account hash as Ethereum (reused from {@link EthKeys}); only the textual encoding differs:
 * prepend the {@code 0x41} mainnet prefix to the 20 address bytes and Base58Check-encode it (see
 * {@link Base58#encodeChecked}). Result is a {@code T…} address.
 *
 * <p><b>Demo-grade key handling</b> (same caveat as {@link EthKeys}): production self-custody keeps private
 * keys offline / in an HSM and imports only watch-only public addresses.
 */
public final class TronKeys {

    private static final int MAINNET_PREFIX = 0x41;

    private TronKeys() {
    }

    /** TRON Base58Check ({@code T…}) address for a secp256k1 private key in [1, n-1]. */
    public static String addressFromPrivateKey(BigInteger priv) {
        byte[] addr20 = EthKeys.addressBytesFromPrivateKey(priv);
        byte[] payload = new byte[21];
        payload[0] = (byte) MAINNET_PREFIX;
        System.arraycopy(addr20, 0, payload, 1, 20);
        return Base58.encodeChecked(payload);
    }

    /** True iff {@code address} is a well-formed TRON address: Base58Check, 0x41 prefix, valid checksum. */
    public static boolean isValidAddress(String address) {
        byte[] payload = Base58.verifyChecked(address);
        return payload != null && payload.length == 21 && (payload[0] & 0xff) == MAINNET_PREFIX;
    }
}
