package com.truholdem.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Per-user KYC verification state. Updated by the KYC provider's webhook (provider-agnostic: the inbound
 * callback maps to {@code status}). Withdrawals are gated on {@link KycStatus#VERIFIED}.
 */
@Entity
@Table(name = "kyc_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_kyc_user", columnNames = "user_id"))
public class KycRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private KycStatus status;

    @Column(length = 64)
    private String provider;

    @Column(name = "provider_ref", length = 128)
    private String providerRef;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KycRecord() {
    }

    public KycRecord(UUID userId, KycStatus status) {
        this.userId = userId;
        this.status = status;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void update(KycStatus status, String provider, String providerRef) {
        this.status = status;
        this.provider = provider;
        this.providerRef = providerRef;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public KycStatus getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRef() {
        return providerRef;
    }
}
