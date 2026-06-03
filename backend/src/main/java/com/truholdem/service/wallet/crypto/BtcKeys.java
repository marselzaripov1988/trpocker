package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bitcoin legacy P2PKH ({@code 1…}) address derivation in pure Java. Uses the secp256k1 public key from
 * {@link EthKeys} in COMPRESSED form (33 bytes), then the standard {@code HASH160 = RIPEMD160(SHA-256(pubkey))}
 * and Base58Check with the {@code 0x00} mainnet version byte. SegWit (bech32 {@code bc1…}) and Taproot are
 * intentionally not implemented here (separate encodings) — P2PKH is universally accepted.
 *
 * <p><b>Demo-grade key handling</b> (same caveat as {@link EthKeys}): production self-custody keeps private
 * keys offline / in an HSM and imports only watch-only public addresses.
 */
public final class BtcKeys {

    private static final int P2PKH_VERSION = 0x00;

    private BtcKeys() {
    }

    /** Compressed secp256k1 public key (33 bytes): 0x02/0x03 parity prefix + 32-byte X. */
    public static byte[] compressedPublicKey(BigInteger priv) {
        byte[] xy = EthKeys.publicKeyBytes(priv);
        byte[] compressed = new byte[33];
        compressed[0] = (byte) (((xy[63] & 1) == 0) ? 0x02 : 0x03);
        System.arraycopy(xy, 0, compressed, 1, 32);
        return compressed;
    }

    private static final String MAINNET_HRP = "bc";

    /** Bitcoin legacy P2PKH ({@code 1…}) address for a secp256k1 private key in [1, n-1]. */
    public static String p2pkhAddress(BigInteger priv) {
        byte[] hash160 = hash160(compressedPublicKey(priv));
        byte[] payload = new byte[21];
        payload[0] = (byte) P2PKH_VERSION;
        System.arraycopy(hash160, 0, payload, 1, 20);
        return Base58.encodeChecked(payload);
    }

    /** Native SegWit v0 P2WPKH ({@code bc1q…}) address — same HASH160, bech32-encoded (lower fees). */
    public static String p2wpkhAddress(BigInteger priv) {
        return Bech32.encodeSegwit(MAINNET_HRP, 0, hash160(compressedPublicKey(priv)));
    }

    /** True iff {@code address} is a well-formed mainnet P2PKH address (Base58Check, 0x00 version, checksum). */
    public static boolean isValidP2pkhAddress(String address) {
        if (address == null || address.isEmpty() || address.charAt(0) != '1') {
            return false;
        }
        byte[] payload = Base58.verifyChecked(address);
        return payload != null && payload.length == 21 && (payload[0] & 0xff) == P2PKH_VERSION;
    }

    /** True iff {@code address} is a well-formed mainnet P2WPKH ({@code bc1q…}) address. */
    public static boolean isValidP2wpkhAddress(String address) {
        return Bech32.decodeP2wpkh(MAINNET_HRP, address) != null;
    }

    /** True for any mainnet Bitcoin deposit address we accept (legacy P2PKH or native SegWit P2WPKH). */
    public static boolean isValidAddress(String address) {
        return isValidP2pkhAddress(address) || isValidP2wpkhAddress(address);
    }

    private static byte[] hash160(byte[] data) {
        return Ripemd160.digest(sha256(data));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
