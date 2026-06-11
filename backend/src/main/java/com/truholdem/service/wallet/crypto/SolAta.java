package com.truholdem.service.wallet.crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Solana Associated Token Account (ATA) derivation in pure Java. The ATA is a Program Derived Address (PDA):
 * {@code findProgramAddress([owner, TOKEN_PROGRAM_ID, mint], ASSOCIATED_TOKEN_PROGRAM_ID)}, i.e. the SHA-256 of
 * the seeds + a bump byte + the program id + the {@code "ProgramDerivedAddress"} marker, scanning the bump from
 * 255 downward until the result is <b>off</b> the ed25519 curve (a PDA has no private key by construction).
 *
 * <p>Implements the ed25519 on-curve test (point decompression over GF(2^255-19)) needed for that off-curve
 * check. Reuses {@link Base58} for address encode/decode. The exact (owner, mint) → ATA value is cross-checked
 * end-to-end against {@code solana-test-validator} in the verify slice; here it is unit-tested for structure
 * (deterministic, 32 bytes, off-curve) and the on-curve test is checked against known valid/invalid points.
 */
public final class SolAta {

    /** SPL Token program. */
    public static final String TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    /** SPL Associated Token Account program. */
    public static final String ASSOCIATED_TOKEN_PROGRAM_ID = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";

    private static final byte[] PDA_MARKER = "ProgramDerivedAddress".getBytes(StandardCharsets.US_ASCII);

    // ed25519 field: p = 2^255 - 19, curve constant d = -121665/121666 (mod p).
    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);

    private SolAta() {
    }

    /** Derive the ATA address (base58) for an owner + mint (both base58). */
    public static String deriveAta(String ownerAddress, String mintAddress) {
        return Base58.encode(deriveAtaBytes(Base58.decode(ownerAddress), Base58.decode(mintAddress)));
    }

    /** Derive the 32-byte ATA address for an owner public key + mint (both 32 raw bytes). */
    public static byte[] deriveAtaBytes(byte[] owner32, byte[] mint32) {
        require32(owner32, "owner");
        require32(mint32, "mint");
        return findProgramAddress(
                List.of(owner32, Base58.decode(TOKEN_PROGRAM_ID), mint32),
                Base58.decode(ASSOCIATED_TOKEN_PROGRAM_ID));
    }

    /** Solana {@code findProgramAddress}: the first off-curve SHA-256(seeds || bump || programId || marker),
     *  scanning bump 255→0. Throws if no off-curve address is found (cryptographically negligible). */
    public static byte[] findProgramAddress(List<byte[]> seeds, byte[] programId) {
        for (int bump = 255; bump >= 0; bump--) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (byte[] seed : seeds) {
                buf.writeBytes(seed);
            }
            buf.write(bump);
            buf.writeBytes(programId);
            buf.writeBytes(PDA_MARKER);
            byte[] candidate = sha256(buf.toByteArray());
            if (!isOnCurve(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No off-curve program address found (unreachable)");
    }

    /**
     * True iff the 32-byte little-endian point is a valid ed25519 curve point (decompresses to a real x). Used
     * to reject would-be PDAs that land on the curve. {@code -x^2 + y^2 = 1 + d x^2 y^2} ⇒
     * {@code x^2 = (y^2 - 1) / (d y^2 + 1)}; the point is on-curve iff that x^2 is a quadratic residue mod p.
     */
    public static boolean isOnCurve(byte[] p32) {
        if (p32 == null || p32.length != 32) {
            return false;
        }
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = p32[31 - i];
        }
        BigInteger y = new BigInteger(1, be).clearBit(255); // drop the x-sign bit
        if (y.compareTo(P) >= 0) {
            return false; // non-canonical y encoding
        }
        BigInteger ySq = y.multiply(y).mod(P);
        BigInteger u = ySq.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(ySq).add(BigInteger.ONE).mod(P);
        if (v.signum() == 0) {
            return false;
        }
        BigInteger xSq = u.multiply(v.modInverse(P)).mod(P);
        if (xSq.signum() == 0) {
            return true; // x = 0 is a valid solution
        }
        // Euler's criterion: xSq is a quadratic residue mod p iff xSq^((p-1)/2) == 1.
        return xSq.modPow(P.subtract(BigInteger.ONE).shiftRight(1), P).equals(BigInteger.ONE);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void require32(byte[] b, String name) {
        if (b == null || b.length != 32) {
            throw new IllegalArgumentException(name + " must be 32 bytes");
        }
    }
}
