package com.truholdem.service.wallet;

import java.util.Optional;

/**
 * Resolves KYC at-rest encryption keys by id, enabling key rotation (each document records the key id it was
 * encrypted with) and a pluggable key source. The default implementation reads a config keyring; a KMS-backed
 * provider is a drop-in replacement.
 */
public interface KycKeyProvider {

    /** Key id to encrypt new uploads with, or empty if encryption is disabled. */
    Optional<String> activeKeyId();

    /** The raw AES key bytes for a key id. Throws if the id is unknown. */
    byte[] resolveKey(String keyId);
}
