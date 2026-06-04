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

/**
 * Configuration of a real-money cash (ring) table: its stakes, buy-in bounds, seat count, settlement asset
 * and rake. This is the persistent table definition; live gameplay runs on the in-memory/hot-state engine and
 * a seat session ({@code CashSeat}, a later slice) ties a seated player's stack to their wallet. Amounts are
 * in the asset's major units (like {@link WalletAccount}). Inert unless the cash-game feature is enabled.
 */
@Entity
@Table(name = "cash_tables")
public class CashTable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Column(name = "small_blind", nullable = false, precision = 38, scale = 18)
    private BigDecimal smallBlind;

    @Column(name = "big_blind", nullable = false, precision = 38, scale = 18)
    private BigDecimal bigBlind;

    @Column(name = "min_buy_in", nullable = false, precision = 38, scale = 18)
    private BigDecimal minBuyIn;

    @Column(name = "max_buy_in", nullable = false, precision = 38, scale = 18)
    private BigDecimal maxBuyIn;

    @Column(name = "max_seats", nullable = false)
    private int maxSeats;

    /** Rake taken from each contested pot, in basis points (e.g. 500 = 5%). */
    @Column(name = "rake_basis_points", nullable = false)
    private int rakeBasisPoints;

    /** Maximum rake per pot (asset major units); 0 = no cap. */
    @Column(name = "rake_cap", nullable = false, precision = 38, scale = 18)
    private BigDecimal rakeCap = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CashTable() {
    }

    public CashTable(String name, CryptoAsset asset, BigDecimal smallBlind, BigDecimal bigBlind,
            BigDecimal minBuyIn, BigDecimal maxBuyIn, int maxSeats, int rakeBasisPoints, BigDecimal rakeCap) {
        this.name = name;
        this.asset = asset;
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
        this.minBuyIn = minBuyIn;
        this.maxBuyIn = maxBuyIn;
        this.maxSeats = maxSeats;
        this.rakeBasisPoints = rakeBasisPoints;
        this.rakeCap = rakeCap != null ? rakeCap : BigDecimal.ZERO;
        this.active = true;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public CryptoAsset getAsset() {
        return asset;
    }

    public BigDecimal getSmallBlind() {
        return smallBlind;
    }

    public BigDecimal getBigBlind() {
        return bigBlind;
    }

    public BigDecimal getMinBuyIn() {
        return minBuyIn;
    }

    public BigDecimal getMaxBuyIn() {
        return maxBuyIn;
    }

    public int getMaxSeats() {
        return maxSeats;
    }

    public int getRakeBasisPoints() {
        return rakeBasisPoints;
    }

    public BigDecimal getRakeCap() {
        return rakeCap;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
