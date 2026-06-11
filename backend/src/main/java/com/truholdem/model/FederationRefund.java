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
 * An admin-approved refund of a funded buy-in from a dedicated player wallet (isolated-custody federated
 * pyramid). The player is made whole — refunded the full {@code grossAmount} on-chain ({@code netAmount =
 * grossAmount}, {@code feeAmount = 0}); the operator absorbs the SOL network fee, so the wallet's ATA empties and
 * can be closed to reclaim rent. ({@code feeAmount}/{@code netAmount} are retained for the audit trail and any
 * future fee policy.) Nothing is signed or broadcast until a moderator approves and supplies the destination
 * {@code toAddress}. Mirrors {@link WithdrawalRequest}, but the source is the dedicated wallet (its owner signs
 * offline) rather than the treasury.
 */
@Entity
@Table(name = "federation_refunds",
        indexes = @Index(name = "idx_fedrefund_fed_status", columnList = "federation_id,status"))
public class FederationRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "federation_id", nullable = false)
    private UUID federationId;

    /** The dedicated wallet being refunded (the source token account / authority). */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Column(name = "gross_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal grossAmount;

    @Column(name = "fee_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal feeAmount;

    @Column(name = "net_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal netAmount;

    /** The player's external Solana address to refund to — supplied by the moderator at approval. */
    @Column(name = "to_address", length = 64)
    private String toAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FederationRefundStatus status;

    @Column(name = "tx_id", length = 128)
    private String txId;

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

    protected FederationRefund() {
    }

    public FederationRefund(UUID federationId, UUID walletId, UUID playerId, CryptoAsset asset,
            BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal netAmount) {
        this.federationId = federationId;
        this.walletId = walletId;
        this.playerId = playerId;
        this.asset = asset;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.status = FederationRefundStatus.PENDING_APPROVAL;
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

    /** Moderator approves the refund and sets the destination → APPROVED (ready for the offline signer). */
    public void approve(UUID moderatorId, String toAddress) {
        this.reviewedBy = moderatorId;
        this.reviewedAt = Instant.now();
        this.toAddress = toAddress;
        this.status = FederationRefundStatus.APPROVED;
    }

    public void reject(UUID moderatorId, String reason) {
        this.reviewedBy = moderatorId;
        this.reviewedAt = Instant.now();
        this.rejectionReason = reason;
        this.status = FederationRefundStatus.REJECTED;
    }

    public void markBroadcast(String txId) {
        this.txId = txId;
        this.status = FederationRefundStatus.BROADCAST;
    }

    public void markConfirmed() {
        this.status = FederationRefundStatus.CONFIRMED;
    }

    public void markFailed() {
        this.status = FederationRefundStatus.FAILED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFederationId() {
        return federationId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public CryptoAsset getAsset() {
        return asset;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public String getToAddress() {
        return toAddress;
    }

    public FederationRefundStatus getStatus() {
        return status;
    }

    public String getTxId() {
        return txId;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
