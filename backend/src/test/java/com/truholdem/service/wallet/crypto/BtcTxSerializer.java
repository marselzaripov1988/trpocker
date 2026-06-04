package com.truholdem.service.wallet.crypto;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;

/**
 * Serializes a signed P2WPKH SegWit transaction (BIP-144) to broadcastable raw hex — the offline-finalisation
 * step that turns the unsigned tx + per-input witnesses into bytes {@code sendrawtransaction} accepts. TEST
 * SOURCES ONLY: like the signers, this never ships in the production jar (the server only relays the opaque
 * signed hex). Inputs/outputs reuse {@link BtcSigner.TxIn}/{@link BtcSigner.TxOut} so the bytes match exactly
 * what was signed; {@code txid} bytes are the internal (little-endian) order.
 */
public final class BtcTxSerializer {

    private BtcTxSerializer() {
    }

    /** A P2WPKH witness stack: the DER signature (with SIGHASH byte) and the 33-byte compressed pubkey. */
    public record Witness(byte[] signature, byte[] pubkey) {
    }

    public static String serialize(int version, List<BtcSigner.TxIn> inputs, List<BtcSigner.TxOut> outputs,
            List<Witness> witnesses, long locktime) {
        if (witnesses.size() != inputs.size()) {
            throw new IllegalArgumentException("one witness per input required");
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        writeUint32LE(o, version);
        o.write(0x00); // SegWit marker
        o.write(0x01); // SegWit flag

        writeVarint(o, inputs.size());
        for (BtcSigner.TxIn in : inputs) {
            o.writeBytes(in.txid());          // 32 bytes, internal LE
            writeUint32LE(o, in.vout());
            o.write(0x00);                    // empty scriptSig (witness program)
            writeUint32LE(o, in.sequence());
        }

        writeVarint(o, outputs.size());
        for (BtcSigner.TxOut out : outputs) {
            writeUint64LE(o, out.value());
            writeVarint(o, out.scriptPubKey().length);
            o.writeBytes(out.scriptPubKey());
        }

        for (Witness w : witnesses) {
            writeVarint(o, 2); // two stack items
            writeVarint(o, w.signature().length);
            o.writeBytes(w.signature());
            writeVarint(o, w.pubkey().length);
            o.writeBytes(w.pubkey());
        }

        writeUint32LE(o, locktime);
        return HexFormat.of().formatHex(o.toByteArray());
    }

    private static void writeUint32LE(ByteArrayOutputStream o, long value) {
        for (int i = 0; i < 4; i++) {
            o.write((int) ((value >>> (8 * i)) & 0xff));
        }
    }

    private static void writeUint64LE(ByteArrayOutputStream o, long value) {
        for (int i = 0; i < 8; i++) {
            o.write((int) ((value >>> (8 * i)) & 0xff));
        }
    }

    private static void writeVarint(ByteArrayOutputStream o, long n) {
        if (n < 0xfd) {
            o.write((int) n);
        } else if (n <= 0xffff) {
            o.write(0xfd);
            o.write((int) (n & 0xff));
            o.write((int) ((n >>> 8) & 0xff));
        } else if (n <= 0xffffffffL) {
            o.write(0xfe);
            writeUint32LE(o, n);
        } else {
            o.write(0xff);
            writeUint64LE(o, n);
        }
    }
}
