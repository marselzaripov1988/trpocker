package com.truholdem.model;

import java.math.BigDecimal;
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
import jakarta.persistence.Version;

/**
 * A user's real-money balance for one crypto asset (the authoritative balance; {@link WalletLedgerEntry}
 * is the append-only audit trail). Balance mutations are guarded by an optimistic-lock {@code version} to
 * prevent lost updates under concurrent deposit/withdrawal.
 */
@Entity
@Table(name = "wallet_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uq_wallet_account_user_asset",
                columnNames = {"user_id", "asset"}))
public class WalletAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WalletAccount() {
    }

    public WalletAccount(UUID userId, CryptoAsset asset) {
        this.userId = userId;
        this.asset = asset;
        this.balance = BigDecimal.ZERO;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    /** @throws IllegalStateException if the balance would go negative. */
    public void debit(BigDecimal amount) {
        BigDecimal next = this.balance.subtract(amount);
        if (next.signum() < 0) {
            throw new IllegalStateException("Insufficient balance");
        }
        this.balance = next;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public CryptoAsset getAsset() {
        return asset;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Long getVersion() {
        return version;
    }
}
