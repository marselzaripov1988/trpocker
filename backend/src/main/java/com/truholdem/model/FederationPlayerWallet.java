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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * A dedicated, per-tournament, per-player on-chain wallet for an <b>isolated-custody</b> federated pyramid: the
 * player pays their buy-in on-chain into {@code address} (the wallet owner's USDT ATA). The private key is
 * generated OFFLINE and never lives here — only the public {@code ownerPubkey} (the ed25519 authority) plus its
 * {@code derivationIndex} (so the offline holder re-derives the signing key) and the {@code address} (deposit
 * target). Imported FREE, handed out one-per-player-per-federation (ASSIGNED), and marked FUNDED once the buy-in
 * deposit confirms on-chain. The prize is later assembled by consolidating the FUNDED wallets to the winners.
 */
@Entity
@Table(name = "federation_player_wallets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_fedwallet_index", columnNames = {"federation_id", "derivation_index"}),
                @UniqueConstraint(name = "uk_fedwallet_address", columnNames = {"federation_id", "address"}),
                @UniqueConstraint(name = "uk_fedwallet_player", columnNames = {"federation_id", "assigned_player_id"})
        },
        indexes = @Index(name = "idx_fedwallet_fed_status", columnList = "federation_id,status"))
public class FederationPlayerWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "federation_id", nullable = false)
    private UUID federationId;

    @Column(name = "derivation_index", nullable = false)
    private long derivationIndex;

    /** Public ed25519 authority (base58); the offline holder re-derives its signing key by {@code derivationIndex}. */
    @Column(name = "owner_pubkey", nullable = false, length = 64)
    private String ownerPubkey;

    /** The deposit target: the owner's USDT associated token account (base58). */
    @Column(nullable = false, length = 64)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FederationWalletStatus status;

    @Column(name = "assigned_player_id")
    private UUID assignedPlayerId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    /** The on-chain signature that funded this wallet (set when FUNDED). */
    @Column(name = "deposit_tx_id", length = 128)
    private String depositTxId;

    @Column(name = "funded_amount", precision = 38, scale = 18)
    private BigDecimal fundedAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected FederationPlayerWallet() {
    }

    public FederationPlayerWallet(UUID federationId, long derivationIndex, String ownerPubkey, String address,
            CryptoAsset asset) {
        this.federationId = federationId;
        this.derivationIndex = derivationIndex;
        this.ownerPubkey = ownerPubkey;
        this.address = address;
        this.asset = asset;
        this.status = FederationWalletStatus.FREE;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Hand this free wallet to a player as their dedicated buy-in address. */
    public void assignTo(UUID playerId) {
        this.assignedPlayerId = playerId;
        this.assignedAt = Instant.now();
        this.status = FederationWalletStatus.ASSIGNED;
    }

    /** Mark the on-chain buy-in deposit confirmed. */
    public void markFunded(String depositTxId, BigDecimal amount) {
        this.depositTxId = depositTxId;
        this.fundedAmount = amount;
        this.status = FederationWalletStatus.FUNDED;
    }

    /** Release an assigned-but-unfunded wallet back to the FREE pool (a no-show whose buy-in never landed). */
    public void release() {
        this.assignedPlayerId = null;
        this.assignedAt = null;
        this.status = FederationWalletStatus.FREE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFederationId() {
        return federationId;
    }

    public long getDerivationIndex() {
        return derivationIndex;
    }

    public String getOwnerPubkey() {
        return ownerPubkey;
    }

    public String getAddress() {
        return address;
    }

    public CryptoAsset getAsset() {
        return asset;
    }

    public FederationWalletStatus getStatus() {
        return status;
    }

    public UUID getAssignedPlayerId() {
        return assignedPlayerId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public String getDepositTxId() {
        return depositTxId;
    }

    public BigDecimal getFundedAmount() {
        return fundedAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
