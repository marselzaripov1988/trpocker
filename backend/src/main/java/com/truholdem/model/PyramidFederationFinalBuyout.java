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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A federation-level buy-out in a buy-up federated pyramid: a player buys a guaranteed seat among the
 * finalists, bypassing the shards. It claims (and closes) one empty shard's slot — the buyer becomes that
 * shard's finalist directly. Price = a whole shard's buy-ins ({@code shardSize × buyIn}). One per player and
 * one per shard slot (DB-enforced).
 */
@Entity
@Table(name = "pyramid_federation_final_buyouts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_federation_final_buyout_player",
                        columnNames = {"federation_id", "buyer_player_id"}),
                @UniqueConstraint(name = "uq_federation_final_buyout_seat",
                        columnNames = {"federation_id", "shard_index"})
        })
public class PyramidFederationFinalBuyout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "federation_id", nullable = false)
    private UUID federationId;

    @Column(name = "buyer_player_id", nullable = false)
    private UUID buyerPlayerId;

    /** The shard slot claimed (and closed) — the buyer takes this shard's finalist seat. */
    @Column(name = "shard_index", nullable = false)
    private int shardIndex;

    @Column(name = "price_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal priceAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PyramidFederationFinalBuyout() {
    }

    public PyramidFederationFinalBuyout(UUID federationId, UUID buyerPlayerId, int shardIndex,
            BigDecimal priceAmount, CryptoAsset asset) {
        this.federationId = federationId;
        this.buyerPlayerId = buyerPlayerId;
        this.shardIndex = shardIndex;
        this.priceAmount = priceAmount;
        this.asset = asset;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getFederationId() {
        return federationId;
    }

    public UUID getBuyerPlayerId() {
        return buyerPlayerId;
    }

    public int getShardIndex() {
        return shardIndex;
    }

    public BigDecimal getPriceAmount() {
        return priceAmount;
    }

    public CryptoAsset getAsset() {
        return asset;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
