package com.truholdem.service.wallet.crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Bitcoin native-SegWit (P2WPKH) signing — the offline half of a PSBT spend. Computes the BIP-143 sighash
 * and a DER-encoded ECDSA signature (RFC-6979 + low-s, via {@link EcdsaSecp256k1}); these are exactly the
 * values a watch-only coordinator's PSBT needs finalised into the input witness. Pure Java, no node.
 *
 * <p>Test-sources only — signing touches private keys and must never ship in the server jar. The PSBT
 * parse/finalise + UTXO selection are the online coordinator's job (the offline signer just produces the
 * signature for each input from the prevout data the PSBT carries).
 */
public final class BtcSigner {

    private BtcSigner() {
    }

    /** A transaction input outpoint + sequence (txid in internal byte order, as carried by the PSBT). */
    public record TxIn(byte[] txid, long vout, long sequence) {
    }

    /** A transaction output: value (satoshis) + scriptPubKey. */
    public record TxOut(long value, byte[] scriptPubKey) {
    }

    /** BIP-143 sighash (SIGHASH_ALL) for signing P2WPKH input {@code inputIndex}, spending {@code amount}. */
    public static byte[] bip143SighashP2wpkh(int version, List<TxIn> inputs, List<TxOut> outputs,
            int inputIndex, byte[] scriptCode, long amount, long locktime) {
        ByteArrayOutputStream preimage = new ByteArrayOutputStream();
        writeLE(preimage, version, 4);

        ByteArrayOutputStream prevouts = new ByteArrayOutputStream();
        for (TxIn in : inputs) {
            prevouts.writeBytes(in.txid());
            writeLE(prevouts, in.vout(), 4);
        }
        preimage.writeBytes(dsha256(prevouts.toByteArray()));

        ByteArrayOutputStream sequences = new ByteArrayOutputStream();
        for (TxIn in : inputs) {
            writeLE(sequences, in.sequence(), 4);
        }
        preimage.writeBytes(dsha256(sequences.toByteArray()));

        TxIn self = inputs.get(inputIndex);
        preimage.writeBytes(self.txid());
        writeLE(preimage, self.vout(), 4);
        writeVarInt(preimage, scriptCode.length);
        preimage.writeBytes(scriptCode);
        writeLE(preimage, amount, 8);
        writeLE(preimage, self.sequence(), 4);

        ByteArrayOutputStream outs = new ByteArrayOutputStream();
        for (TxOut out : outputs) {
            writeLE(outs, out.value(), 8);
            writeVarInt(outs, out.scriptPubKey().length);
            outs.writeBytes(out.scriptPubKey());
        }
        preimage.writeBytes(dsha256(outs.toByteArray()));

        writeLE(preimage, locktime, 4);
        writeLE(preimage, 1, 4); // SIGHASH_ALL
        return dsha256(preimage.toByteArray());
    }

    /** The P2PKH scriptCode ({@code 76a914<hash160>88ac}) for the input's signing key (BIP-143 §P2WPKH). */
    public static byte[] p2wpkhScriptCode(BigInteger privateKey) {
        byte[] keyHash = hash160(BtcKeys.compressedPublicKey(privateKey));
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.writeBytes(new byte[] { 0x76, (byte) 0xa9, 0x14 });
        s.writeBytes(keyHash);
        s.writeBytes(new byte[] { (byte) 0x88, (byte) 0xac });
        return s.toByteArray();
    }

    /** Sign a P2WPKH input → DER signature + the SIGHASH_ALL byte (the witness signature element). */
    public static byte[] signP2wpkhInput(BigInteger privateKey, int version, List<TxIn> inputs,
            List<TxOut> outputs, int inputIndex, long amount, long locktime) {
        byte[] sighash = bip143SighashP2wpkh(version, inputs, outputs, inputIndex,
                p2wpkhScriptCode(privateKey), amount, locktime);
        EcdsaSecp256k1.Signature sig = EcdsaSecp256k1.sign(sighash, privateKey);
        byte[] der = der(sig.r(), sig.s());
        byte[] out = new byte[der.length + 1];
        System.arraycopy(der, 0, out, 0, der.length);
        out[der.length] = 0x01; // SIGHASH_ALL
        return out;
    }

    /** Strict-DER encode (r, s). BigInteger.toByteArray() already yields minimal big-endian with the DER
     *  leading-zero rule (a 0x00 is added iff the high bit would otherwise be set). */
    static byte[] der(BigInteger r, BigInteger s) {
        byte[] rb = r.toByteArray();
        byte[] sb = s.toByteArray();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x02);
        body.write(rb.length);
        body.writeBytes(rb);
        body.write(0x02);
        body.write(sb.length);
        body.writeBytes(sb);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.write(b.length);
        out.writeBytes(b);
        return out.toByteArray();
    }

    private static byte[] hash160(byte[] data) {
        return Ripemd160.digest(sha256(data));
    }

    private static byte[] dsha256(byte[] data) {
        return sha256(sha256(data));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void writeLE(ByteArrayOutputStream out, long value, int numBytes) {
        for (int i = 0; i < numBytes; i++) {
            out.write((int) ((value >>> (8 * i)) & 0xff));
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, long n) {
        if (n < 0xfd) {
            out.write((int) n);
        } else if (n <= 0xffff) {
            out.write(0xfd);
            writeLE(out, n, 2);
        } else {
            out.write(0xfe);
            writeLE(out, n, 4);
        }
    }
}
