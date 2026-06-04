package com.truholdem.dto.wallet;

/**
 * Outcome of a KYC re-encryption sweep (key rotation / provider migration): how many documents were
 * re-encrypted under the active key, how many were left untouched (already current, or plaintext with
 * encryption disabled), and the total scanned.
 */
public record KycReEncryptResult(int reEncrypted, int skipped, int total) {
}
