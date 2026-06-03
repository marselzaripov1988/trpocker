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
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A crypto withdrawal. Created only after the KYC gate passes and funds are debited (so it never exists in
 * a "needs KYC" limbo — the debit and the request are written in one transaction). Then it is broadcast to
 * the network and tracked to confirmation.
 */
@Entity
@Table(name = "withdrawal_requests",
        indexes = @Index(name = "idx_withdrawal_user", columnList = "user_id"))
public class WithdrawalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Column(name = "to_address", nullable = false, length = 128)
    private String toAddress;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WithdrawalStatus status;

    @Column(name = "tx_id", length = 128)
    private String txId;

    /** Moderator who approved/rejected the request (manual-approval flow); null until reviewed. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "rejection_reason", length = 512)
    private String rejectionReason;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WithdrawalRequest() {
    }

    public WithdrawalRequest(UUID userId, CryptoAsset asset, String toAddress, BigDecimal amount) {
        this(userId, asset, toAddress, amount, WithdrawalStatus.APPROVED);
    }

    public WithdrawalRequest(UUID userId, CryptoAsset asset, String toAddress, BigDecimal amount,
            WithdrawalStatus status) {
        this.userId = userId;
        this.asset = asset;
        this.toAddress = toAddress;
        this.amount = amount;
        this.status = status;
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

    public void markBroadcast(String txId) {
        this.txId = txId;
        this.status = WithdrawalStatus.BROADCAST;
    }

    public void markConfirmed() {
        this.status = WithdrawalStatus.CONFIRMED;
    }

    /** Moderator approves a PENDING_APPROVAL request → APPROVED (ready to broadcast). */
    public void approve(UUID moderatorId) {
        this.reviewedBy = moderatorId;
        this.reviewedAt = Instant.now();
        this.status = WithdrawalStatus.APPROVED;
    }

    /** Moderator rejects a PENDING_APPROVAL request → REJECTED (the debit is reversed by the service). */
    public void reject(UUID moderatorId, String reason) {
        this.reviewedBy = moderatorId;
        this.reviewedAt = Instant.now();
        this.rejectionReason = reason;
        this.status = WithdrawalStatus.REJECTED;
    }

    public void markFailed() {
        this.status = WithdrawalStatus.FAILED;
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

    public String getToAddress() {
        return toAddress;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public WithdrawalStatus getStatus() {
        return status;
    }

    public String getTxId() {
        return txId;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
