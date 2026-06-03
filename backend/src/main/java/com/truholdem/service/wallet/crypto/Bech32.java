package com.truholdem.service.wallet.crypto;

import java.util.ArrayList;
import java.util.List;

/**
 * Bech32 (BIP-173) encoding for native SegWit v0 addresses ({@code bc1q…}) in pure Java. Encodes/validates a
 * witness program with the human-readable prefix + 6-symbol error-detecting checksum. Only SegWit v0 is
 * supported here (P2WPKH/P2WSH); v1+ (Taproot, {@code bc1p…}) uses the bech32m variant and is out of scope.
 */
public final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = { 0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3 };
    private static final int BECH32_CONST = 1;            // SegWit v0  (BIP-173)
    private static final int BECH32M_CONST = 0x2bc830a3;  // SegWit v1+ (BIP-350, e.g. Taproot)

    private Bech32() {
    }

    /** Encode a SegWit address: {@code hrp} + witness version + program. v0 uses bech32, v1+ uses bech32m. */
    public static String encodeSegwit(String hrp, int witnessVersion, byte[] program) {
        int[] programBits = new int[program.length];
        for (int i = 0; i < program.length; i++) {
            programBits[i] = program[i] & 0xff;
        }
        int[] converted = convertBits(programBits, 8, 5, true);
        int[] data = new int[1 + converted.length];
        data[0] = witnessVersion;
        System.arraycopy(converted, 0, data, 1, converted.length);

        int[] checksum = createChecksum(hrp, data, constantFor(witnessVersion));
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (int d : data) {
            sb.append(CHARSET.charAt(d));
        }
        for (int d : checksum) {
            sb.append(CHARSET.charAt(d));
        }
        return sb.toString();
    }

    /** Decode a P2WPKH ({@code bc1q…}, v0, 20-byte) address; returns the witness program or null. */
    public static byte[] decodeP2wpkh(String hrp, String address) {
        return decodeSegwit(hrp, address, 0, 20);
    }

    /** Decode a P2TR ({@code bc1p…}, v1, 32-byte) address; returns the witness program or null. */
    public static byte[] decodeP2tr(String hrp, String address) {
        return decodeSegwit(hrp, address, 1, 32);
    }

    private static byte[] decodeSegwit(String hrp, String address, int expectedWitver, int expectedProgramLen) {
        if (address == null || address.length() < 8 || address.length() > 90) {
            return null;
        }
        String lower = address.toLowerCase();
        if (!address.equals(lower) && !address.equals(address.toUpperCase())) {
            return null; // mixed case is invalid
        }
        address = lower;
        int sep = address.lastIndexOf('1');
        if (sep < 1 || sep + 7 > address.length()) {
            return null;
        }
        if (!address.substring(0, sep).equals(hrp)) {
            return null;
        }
        String dataPart = address.substring(sep + 1);
        int[] values = new int[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int d = CHARSET.indexOf(dataPart.charAt(i));
            if (d < 0) {
                return null;
            }
            values[i] = d;
        }
        int dataLen = values.length - 6;
        if (dataLen < 1 || values[0] != expectedWitver) {
            return null;
        }
        if (!verifyChecksum(hrp, values, constantFor(expectedWitver))) {
            return null;
        }
        int[] program5 = new int[dataLen - 1];
        System.arraycopy(values, 1, program5, 0, dataLen - 1);
        int[] program8 = convertBits(program5, 5, 8, false);
        if (program8 == null || program8.length != expectedProgramLen) {
            return null;
        }
        byte[] out = new byte[program8.length];
        for (int i = 0; i < program8.length; i++) {
            out[i] = (byte) program8[i];
        }
        return out;
    }

    private static int constantFor(int witnessVersion) {
        return witnessVersion == 0 ? BECH32_CONST : BECH32M_CONST;
    }

    private static int polymod(int[] values) {
        int chk = 1;
        for (int v : values) {
            int b = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((b >>> i) & 1) != 0) {
                    chk ^= GEN[i];
                }
            }
        }
        return chk;
    }

    private static int[] hrpExpand(String hrp) {
        int n = hrp.length();
        int[] out = new int[n * 2 + 1];
        for (int i = 0; i < n; i++) {
            out[i] = hrp.charAt(i) >>> 5;
            out[n + 1 + i] = hrp.charAt(i) & 31;
        }
        return out;
    }

    private static int[] createChecksum(String hrp, int[] data, int constant) {
        int[] expand = hrpExpand(hrp);
        int[] values = new int[expand.length + data.length + 6];
        System.arraycopy(expand, 0, values, 0, expand.length);
        System.arraycopy(data, 0, values, expand.length, data.length);
        int pm = polymod(values) ^ constant;
        int[] checksum = new int[6];
        for (int i = 0; i < 6; i++) {
            checksum[i] = (pm >>> (5 * (5 - i))) & 31;
        }
        return checksum;
    }

    private static boolean verifyChecksum(String hrp, int[] data, int constant) {
        int[] expand = hrpExpand(hrp);
        int[] values = new int[expand.length + data.length];
        System.arraycopy(expand, 0, values, 0, expand.length);
        System.arraycopy(data, 0, values, expand.length, data.length);
        return polymod(values) == constant;
    }

    private static int[] convertBits(int[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;
        List<Integer> ret = new ArrayList<>();
        for (int value : data) {
            if (value < 0 || (value >>> fromBits) != 0) {
                return null;
            }
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                ret.add((acc >>> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) {
                ret.add((acc << (toBits - bits)) & maxv);
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            return null;
        }
        int[] out = new int[ret.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ret.get(i);
        }
        return out;
    }
}
