package com.truholdem.service.wallet.storage;

/**
 * Storage backend for KYC verification media (opaque keyed blobs). Implementations: a local filesystem
 * (default) or S3/MinIO object storage. The caller (KycVerificationService) handles encryption + metadata;
 * the storage only persists bytes by key.
 */
public interface KycStorage {

    void store(String key, byte[] content);

    byte[] load(String key);

    void delete(String key);
}
