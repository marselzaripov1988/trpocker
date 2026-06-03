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
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * One pre-generated, watch-only deposit address in the offline pool. The matching private key is generated
 * and kept OFFLINE (never on this server); only the public {@code address} (plus its {@code derivationIndex},
 * which lets the offline holder re-derive the signing key) lives here. Addresses are imported FREE and handed
 * out one-per-user-per-asset as players request a deposit address.
 */
@Entity
@Table(name = "deposit_address_pool",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pool_asset_address", columnNames = {"asset", "address"}),
                @UniqueConstraint(name = "uk_pool_asset_user", columnNames = {"asset", "assigned_user_id"})
        },
        indexes = @Index(name = "idx_pool_asset_status", columnList = "asset,status"))
public class DepositAddressPoolEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Column(nullable = false, length = 128)
    private String address;

    /** Index of this address within the offline seed's derivation (re-derive the key offline by index). */
    @Column(name = "derivation_index", nullable = false)
    private long derivationIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DepositAddressStatus status;

    @Column(name = "assigned_user_id")
    private UUID assignedUserId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected DepositAddressPoolEntry() {
    }

    public DepositAddressPoolEntry(CryptoAsset asset, String address, long derivationIndex) {
        this.asset = asset;
        this.address = address;
        this.derivationIndex = derivationIndex;
        this.status = DepositAddressStatus.FREE;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Claim this free address for a user (idempotency is enforced by the caller + the unique constraint). */
    public void assignTo(UUID userId) {
        this.assignedUserId = userId;
        this.assignedAt = Instant.now();
        this.status = DepositAddressStatus.ASSIGNED;
    }

    public UUID getId() {
        return id;
    }

    public CryptoAsset getAsset() {
        return asset;
    }

    public String getAddress() {
        return address;
    }

    public long getDerivationIndex() {
        return derivationIndex;
    }

    public DepositAddressStatus getStatus() {
        return status;
    }

    public UUID getAssignedUserId() {
        return assignedUserId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
