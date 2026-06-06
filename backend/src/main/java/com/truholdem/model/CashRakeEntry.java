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
 * House-revenue record: the rake taken from one contested pot at a real-money cash (ring) table. The sum of a
 * table's entries is its accrued rake (house revenue). One row per raked pot, idempotent on
 * {@code idempotencyKey} (the settling hand/game id) so re-running a pot settlement never double-counts.
 * Amounts are in the table's asset major units.
 */
@Entity
@Table(name = "cash_rake_entries")
public class CashRakeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cash_table_id", nullable = false)
    private UUID cashTableId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    /** The contested pot the rake was taken from (asset major units). */
    @Column(name = "pot_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal potAmount;

    /** The rake taken from the pot (asset major units). */
    @Column(name = "rake_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal rakeAmount;

    /** Settling hand/game id; unique so a re-settled pot is recorded once. */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    protected CashRakeEntry() {
    }

    public CashRakeEntry(UUID cashTableId, CryptoAsset asset, BigDecimal potAmount, BigDecimal rakeAmount,
            String idempotencyKey) {
        this.cashTableId = cashTableId;
        this.asset = asset;
        this.potAmount = potAmount;
        this.rakeAmount = rakeAmount;
        this.idempotencyKey = idempotencyKey;
    }

    @PrePersist
    void onCreate() {
        this.collectedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCashTableId() {
        return cashTableId;
    }

    public CryptoAsset getAsset() {
        return asset;
    }

    public BigDecimal getPotAmount() {
        return potAmount;
    }

    public BigDecimal getRakeAmount() {
        return rakeAmount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }
}
