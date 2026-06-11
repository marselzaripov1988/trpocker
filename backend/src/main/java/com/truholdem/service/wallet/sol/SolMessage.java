package com.truholdem.service.wallet.sol;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a legacy Solana transaction <b>message</b> (the bytes that get signed) from a fee payer, a recent
 * blockhash and a list of instructions. Handles the account-key de-duplication + ordering Solana requires —
 * writable-signers, readonly-signers, writable-non-signers, readonly-non-signers, with the fee payer first —
 * the 3-byte header, and the compact-array serialization of keys + instructions.
 *
 * <p>Wire format (legacy): {@code header(3) || shortvec(accountKeys)·32 || blockhash·32 || shortvec(instructions)}
 * where each instruction is {@code programIdIndex(u8) || shortvec(accountIndices)·u8 || shortvec(data)}.
 */
final class SolMessage {

    /** An account referenced by an instruction, with its signer/writable requirements. */
    record AccountMeta(byte[] pubkey, boolean signer, boolean writable) {
    }

    /** A compiled instruction: the program, the accounts it touches, and its opaque data. */
    record Instruction(byte[] programId, List<AccountMeta> accounts, byte[] data) {
    }

    private SolMessage() {
    }

    /** Serialize the signable message bytes. */
    static byte[] compile(byte[] feePayer, byte[] recentBlockhash, List<Instruction> instructions) {
        if (feePayer == null || feePayer.length != 32) {
            throw new IllegalArgumentException("feePayer must be 32 bytes");
        }
        if (recentBlockhash == null || recentBlockhash.length != 32) {
            throw new IllegalArgumentException("recentBlockhash must be 32 bytes");
        }

        // 1. Merge every referenced account (fee payer first = guaranteed index 0; signer/writable flags OR-ed).
        Map<String, Meta> merged = new LinkedHashMap<>();
        upsert(merged, feePayer, true, true);
        for (Instruction ix : instructions) {
            for (AccountMeta am : ix.accounts()) {
                upsert(merged, am.pubkey(), am.signer(), am.writable());
            }
            upsert(merged, ix.programId(), false, false); // program ids are readonly non-signers
        }

        // 2. Order: writable signers, readonly signers, writable non-signers, readonly non-signers
        //    (insertion order preserved within each bucket, so the fee payer stays first).
        List<Meta> ordered = new ArrayList<>();
        addWhere(ordered, merged.values(), true, true);
        addWhere(ordered, merged.values(), true, false);
        addWhere(ordered, merged.values(), false, true);
        addWhere(ordered, merged.values(), false, false);

        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            index.put(key(ordered.get(i).pubkey), i);
        }

        int numSigners = (int) ordered.stream().filter(m -> m.signer).count();
        int numReadonlySigned = (int) ordered.stream().filter(m -> m.signer && !m.writable).count();
        int numReadonlyUnsigned = (int) ordered.stream().filter(m -> !m.signer && !m.writable).count();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(numSigners);
        out.write(numReadonlySigned);
        out.write(numReadonlyUnsigned);

        out.writeBytes(SolShortVec.encodeLength(ordered.size()));
        for (Meta m : ordered) {
            out.writeBytes(m.pubkey);
        }
        out.writeBytes(recentBlockhash);

        out.writeBytes(SolShortVec.encodeLength(instructions.size()));
        for (Instruction ix : instructions) {
            out.write(index.get(key(ix.programId())));
            out.writeBytes(SolShortVec.encodeLength(ix.accounts().size()));
            for (AccountMeta am : ix.accounts()) {
                out.write(index.get(key(am.pubkey())));
            }
            byte[] data = ix.data();
            out.writeBytes(SolShortVec.encodeLength(data.length));
            out.writeBytes(data);
        }
        return out.toByteArray();
    }

    private static void upsert(Map<String, Meta> map, byte[] pubkey, boolean signer, boolean writable) {
        if (pubkey == null || pubkey.length != 32) {
            throw new IllegalArgumentException("account pubkey must be 32 bytes");
        }
        Meta m = map.get(key(pubkey));
        if (m == null) {
            map.put(key(pubkey), new Meta(pubkey, signer, writable));
        } else {
            m.signer |= signer;
            m.writable |= writable;
        }
    }

    private static void addWhere(List<Meta> dst, Iterable<Meta> all, boolean signer, boolean writable) {
        for (Meta m : all) {
            if (m.signer == signer && m.writable == writable) {
                dst.add(m);
            }
        }
    }

    private static String key(byte[] pubkey) {
        return java.util.HexFormat.of().formatHex(pubkey);
    }

    private static final class Meta {
        final byte[] pubkey;
        boolean signer;
        boolean writable;

        Meta(byte[] pubkey, boolean signer, boolean writable) {
            this.pubkey = pubkey;
            this.signer = signer;
            this.writable = writable;
        }
    }
}
