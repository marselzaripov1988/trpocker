package com.truholdem.service.wallet.crypto;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * TRON (TRC-20 / TRX) signing — the offline step. A TRON transaction id is {@code SHA-256(raw_data)} (the
 * protobuf-serialised body, built online from TronGrid), and the signature is a 65-byte recoverable
 * {@code r(32) || s(32) || recId(1)} over that id (RFC-6979 + low-s, via {@link EcdsaSecp256k1}). The online
 * side attaches this signature to the transaction and broadcasts it. Pure Java, no node.
 *
 * <p>Test-sources only — signing touches private keys and must never ship in the server jar. Building the
 * {@code raw_data} (TransferContract / TriggerSmartContract + a recent block ref) is the online coordinator's
 * job; this signer just signs the resulting id.
 */
public final class TronSigner {

    private TronSigner() {
    }

    /** {@code SHA-256(raw_data)} — the TRON transaction id. */
    public static byte[] txId(byte[] rawData) {
        return sha256(rawData);
    }

    /** 65-byte recoverable signature {@code r || s || recId} over {@code SHA-256(raw_data)}. */
    public static byte[] sign(byte[] rawData, BigInteger privateKey) {
        EcdsaSecp256k1.Signature sig = EcdsaSecp256k1.sign(txId(rawData), privateKey);
        byte[] out = new byte[65];
        System.arraycopy(EthKeys.to32(sig.r()), 0, out, 0, 32);
        System.arraycopy(EthKeys.to32(sig.s()), 0, out, 32, 32);
        out[64] = (byte) sig.recId();
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
