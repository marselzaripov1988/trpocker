package com.truholdem.service.wallet;

import java.util.Optional;

/**
 * Supplies KYC at-rest encryption keys. Each document records the {@link DataKey#keyId() key id} it was
 * encrypted with, so the source can rotate keys (config keyring) or mint a fresh per-document data key
 * (envelope encryption against a KMS) — the read path just asks for the recorded id back.
 *
 * <p>Two implementations: {@code ConfigKycKeyProvider} (a config-held keyring, the default) and
 * {@code KmsKycKeyProvider} (AWS KMS envelope encryption, where the recorded key id is the CMK-wrapped data
 * key and the raw key never lives in config). They are mutually exclusive via
 * {@code app.payments.kyc-key-provider}.
 */
public interface KycKeyProvider {

    /** A data key for encrypting one document: the raw AES key plus the id to persist with the document. */
    record DataKey(byte[] key, String keyId) {
    }

    /**
     * A data key to encrypt a new upload, or empty if encryption is disabled. For a config keyring this is the
     * active key (stable id); for KMS it is a freshly-generated, CMK-wrapped data key (id unique per upload).
     */
    Optional<DataKey> newDataKey();

    /** The raw AES key bytes for a previously-recorded key id (decrypt path). Throws if the id is unknown. */
    byte[] resolveKey(String keyId);
}
