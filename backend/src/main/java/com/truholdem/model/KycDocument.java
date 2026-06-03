package com.truholdem.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Metadata for a KYC verification document (e.g. a video of the user holding their passport). The file bytes
 * live on disk under {@code app.payments.kyc-storage-dir} (keyed by {@code storageKey}); only metadata is in
 * the DB. Sensitive PII — the bytes are served only to ADMIN.
 */
@Entity
@Table(name = "kyc_documents",
        indexes = @Index(name = "idx_kyc_doc_user", columnList = "user_id"))
public class KycDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "original_filename", length = 256)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    /** Opaque on-disk filename (a random UUID) under the KYC storage dir — never the user-supplied name. */
    @Column(name = "storage_key", nullable = false, length = 128)
    private String storageKey;

    /** Whether the on-disk bytes are AES-GCM encrypted (sha256 is over the original plaintext either way). */
    @Column(nullable = false)
    private boolean encrypted;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected KycDocument() {
    }

    public KycDocument(UUID userId, String originalFilename, String contentType, long sizeBytes,
            String sha256, String storageKey, boolean encrypted) {
        this.userId = userId;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.encrypted = encrypted;
    }

    @PrePersist
    void onCreate() {
        this.uploadedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public boolean isEncrypted() {
        return encrypted;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
