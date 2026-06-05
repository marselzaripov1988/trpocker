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
 * A pre-start buy-out of a guaranteed higher-level seat in a buy-up pyramid tournament. A registered player
 * buys one {@code seatIndex} at {@code level} (one of the feeder tables at level-1) for {@code priceAmount}
 * (the sub-pyramid buy-ins). Uniqueness enforces the rules: one buy-out per player, and one buyer per seat.
 */
@Entity
@Table(name = "pyramid_buyouts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_pyramid_buyout_player",
                        columnNames = {"tournament_id", "buyer_player_id"}),
                @UniqueConstraint(name = "uq_pyramid_buyout_seat",
                        columnNames = {"tournament_id", "level", "seat_index"})
        })
public class PyramidBuyout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tournament_id", nullable = false)
    private UUID tournamentId;

    @Column(name = "buyer_player_id", nullable = false)
    private UUID buyerPlayerId;

    /** Level bought into (2 = first level above the floor). */
    @Column(nullable = false)
    private int level;

    /** Index of the bought seat among the buyable seats at this level (0-based feeder table index). */
    @Column(name = "seat_index", nullable = false)
    private int seatIndex;

    @Column(name = "price_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal priceAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PyramidBuyout() {
    }

    public PyramidBuyout(UUID tournamentId, UUID buyerPlayerId, int level, int seatIndex,
            BigDecimal priceAmount, CryptoAsset asset) {
        this.tournamentId = tournamentId;
        this.buyerPlayerId = buyerPlayerId;
        this.level = level;
        this.seatIndex = seatIndex;
        this.priceAmount = priceAmount;
        this.asset = asset;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTournamentId() {
        return tournamentId;
    }

    public UUID getBuyerPlayerId() {
        return buyerPlayerId;
    }

    public int getLevel() {
        return level;
    }

    public int getSeatIndex() {
        return seatIndex;
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
