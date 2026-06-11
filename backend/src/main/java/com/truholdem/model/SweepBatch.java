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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A deposit→treasury consolidation (sweep): one on-chain transaction that gathers UTXOs from many watch-only
 * deposit-pool addresses into the single treasury address. This is an <b>internal custody move</b> — it never
 * touches a {@link WalletAccount} or the user ledger, so it does not affect the solvency-monitor liabilities;
 * this row is just its audit trail + on-chain state machine (mirrors {@link WithdrawalRequest} minus the
 * moderator/KYC gate). Signing always happens offline; this row only records assembled amounts + the txid.
 */
@Entity
@Table(name = "sweep_batches",
        indexes = @Index(name = "idx_sweep_asset_status", columnList = "asset,status"))
public class SweepBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SweepBatchStatus status;

    /** The treasury address the swept funds are consolidated into. */
    @Column(name = "to_address", nullable = false, length = 128)
    private String toAddress;

    /** Number of deposit-address UTXOs gathered into this sweep. */
    @Column(name = "input_count", nullable = false)
    private int inputCount;

    /** Total value of the gathered inputs, in the asset's smallest on-chain unit (satoshis for BTC). */
    @Column(name = "total_in_value_sat", nullable = false)
    private long totalInValueSat;

    /** Network fee paid, in the asset's smallest on-chain unit. */
    @Column(name = "fee_sat", nullable = false)
    private long feeSat;

    @Column(name = "tx_id", length = 128)
    private String txId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SweepBatch() {
    }

    public SweepBatch(CryptoAsset asset, String toAddress, int inputCount, long totalInValueSat, long feeSat) {
        this.asset = asset;
        this.toAddress = toAddress;
        this.inputCount = inputCount;
        this.totalInValueSat = totalInValueSat;
        this.feeSat = feeSat;
        this.status = SweepBatchStatus.PLANNED;
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
        this.status = SweepBatchStatus.BROADCAST;
    }

    public void markConfirmed() {
        this.status = SweepBatchStatus.CONFIRMED;
    }

    public void markFailed() {
        this.status = SweepBatchStatus.FAILED;
    }

    public UUID getId() {
        return id;
    }

    public CryptoAsset getAsset() {
        return asset;
    }

    public SweepBatchStatus getStatus() {
        return status;
    }

    public String getToAddress() {
        return toAddress;
    }

    public int getInputCount() {
        return inputCount;
    }

    public long getTotalInValueSat() {
        return totalInValueSat;
    }

    public long getFeeSat() {
        return feeSat;
    }

    public String getTxId() {
        return txId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
