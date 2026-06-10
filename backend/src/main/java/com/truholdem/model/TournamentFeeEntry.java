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
 * House-revenue record: the commission taken off one crypto prize pool when a real-money tournament (or
 * federation) pays out. Mirrors {@link CashRakeEntry} for the cash-table rake — like it, this is a pure
 * accounting record, <b>not</b> a wallet account, so it never inflates user liabilities. The fee is not moved
 * anywhere on payout; it is simply withheld from the pool (winners receive the net), and the withheld amount
 * stays in custody as house margin. One row per paid-out pool, idempotent on {@code idempotencyKey} (the
 * source tournament/federation id) so a re-run never double-counts. Amounts are in the asset's major units.
 */
@Entity
@Table(name = "tournament_fee_entries")
public class TournamentFeeEntry {

    /** What produced the pool the fee was taken from. */
    public enum SourceType {
        TOURNAMENT,
        FEDERATION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private SourceType sourceType;

    /** The tournament or federation whose pool was raked. */
    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    /** The gross pool before the fee (asset major units). */
    @Column(name = "gross_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal grossAmount;

    /** The commission withheld from the pool (asset major units). */
    @Column(name = "fee_amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal feeAmount;

    /** The fee rate applied, in basis points (e.g. 1000 = 10%). */
    @Column(name = "fee_basis_points", nullable = false)
    private int feeBasisPoints;

    /** Source tournament/federation id; unique so a re-paid pool is recorded once. */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    protected TournamentFeeEntry() {
    }

    public TournamentFeeEntry(SourceType sourceType, UUID sourceId, CryptoAsset asset, BigDecimal grossAmount,
            BigDecimal feeAmount, int feeBasisPoints, String idempotencyKey) {
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.asset = asset;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.feeBasisPoints = feeBasisPoints;
        this.idempotencyKey = idempotencyKey;
    }

    @PrePersist
    void onCreate() {
        this.collectedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
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

    public int getFeeBasisPoints() {
        return feeBasisPoints;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }
}
