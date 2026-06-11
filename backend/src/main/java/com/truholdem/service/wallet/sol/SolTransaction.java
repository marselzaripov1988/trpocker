package com.truholdem.service.wallet.sol;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;

/**
 * Serializes a signed legacy Solana transaction to the base64 form {@code sendTransaction} expects:
 * {@code shortvec(signatures)·64 || message}. The signatures are the offline ed25519 signatures over the
 * compiled message, in the same order as the message's required signers.
 */
final class SolTransaction {

    private SolTransaction() {
    }

    /** base64({@code shortvec(signatures)·64 || message}). */
    static String serializeBase64(List<byte[]> signatures, byte[] message) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(SolShortVec.encodeLength(signatures.size()));
        for (byte[] sig : signatures) {
            if (sig == null || sig.length != 64) {
                throw new IllegalArgumentException("ed25519 signature must be 64 bytes");
            }
            out.writeBytes(sig);
        }
        out.writeBytes(message);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
