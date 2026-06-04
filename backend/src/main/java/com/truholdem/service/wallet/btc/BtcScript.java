package com.truholdem.service.wallet.btc;

import java.util.HexFormat;

import com.truholdem.service.wallet.crypto.Bech32;

/**
 * Bitcoin script / network helpers for the online withdrawal coordinator (pure Java). Maps a network name to
 * its bech32 human-readable prefix and decodes a native-SegWit P2WPKH address into its {@code scriptPubKey}
 * ({@code OP_0 <20-byte program>}). No key material is involved — this is plain decoding.
 */
public final class BtcScript {

    private BtcScript() {
    }

    /** The bech32 hrp for a network name: mainnet→{@code bc}, testnet→{@code tb}, regtest→{@code bcrt}. */
    public static String hrp(String network) {
        return switch (network == null ? "" : network.toLowerCase()) {
            case "mainnet", "main", "" -> "bc";
            case "testnet", "test", "signet" -> "tb";
            case "regtest", "regtest1" -> "bcrt";
            default -> throw new IllegalArgumentException("Unknown BTC network: " + network);
        };
    }

    /** The {@code scriptPubKey} for a P2WPKH (v0, 20-byte) bech32 address on the given network. */
    public static byte[] p2wpkhScriptPubKey(String address, String network) {
        byte[] program = Bech32.decodeP2wpkh(hrp(network), address);
        if (program == null || program.length != 20) {
            throw new IllegalArgumentException("Not a P2WPKH (bech32 v0) address for " + network + ": " + address);
        }
        byte[] script = new byte[22];
        script[0] = 0x00; // OP_0
        script[1] = 0x14; // push 20 bytes
        System.arraycopy(program, 0, script, 2, 20);
        return script;
    }

    public static byte[] hexToBytes(String hex) {
        String s = hex.startsWith("0x") ? hex.substring(2) : hex;
        return HexFormat.of().parseHex(s);
    }

    public static String toHex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }

    /** Reverse a display txid (big-endian hex) into the internal little-endian byte order used in a tx. */
    public static byte[] txidToInternalBytes(String displayTxid) {
        byte[] be = HexFormat.of().parseHex(displayTxid);
        byte[] le = new byte[be.length];
        for (int i = 0; i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }
}
