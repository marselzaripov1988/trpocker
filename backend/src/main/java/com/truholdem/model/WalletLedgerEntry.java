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

/**
 * Append-only audit trail of every balance change. The signed {@code amount} (+credit / −debit) and the
 * resulting {@code balanceAfter} make the wallet auditable. {@code externalTxId} (the on-chain deposit
 * txid) is unique, which is what makes deposit crediting idempotent — a duplicate webhook for the same
 * transaction violates the constraint and is treated as already-applied.
 */
@Entity
@Table(name = "wallet_ledger_entries",
        indexes = {
                @Index(name = "idx_wallet_ledger_user_asset", columnList = "user_id,asset"),
                @Index(name = "uq_wallet_ledger_external_tx", columnList = "external_tx_id", unique = true)
        })
public class WalletLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CryptoAsset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WalletLedgerType type;

    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 38, scale = 18)
    private BigDecimal balanceAfter;

    /** On-chain transaction id for deposits (unique → idempotency); null for non-deposit entries. */
    @Column(name = "external_tx_id", length = 128)
    private String externalTxId;

    /** Originating withdrawal request id for withdrawal entries; null otherwise. */
    @Column(name = "withdrawal_id")
    private UUID withdrawalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WalletLedgerEntry() {
    }

    private WalletLedgerEntry(UUID userId, CryptoAsset asset, WalletLedgerType type,
            BigDecimal amount, BigDecimal balanceAfter, String externalTxId, UUID withdrawalId) {
        this.userId = userId;
        this.asset = asset;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.externalTxId = externalTxId;
        this.withdrawalId = withdrawalId;
    }

    public static WalletLedgerEntry deposit(UUID userId, CryptoAsset asset, BigDecimal amount,
            BigDecimal balanceAfter, String txId) {
        return new WalletLedgerEntry(userId, asset, WalletLedgerType.DEPOSIT, amount, balanceAfter, txId, null);
    }

    public static WalletLedgerEntry withdrawal(UUID userId, CryptoAsset asset, BigDecimal amount,
            BigDecimal balanceAfter, UUID withdrawalId) {
        return new WalletLedgerEntry(userId, asset, WalletLedgerType.WITHDRAWAL,
                amount.negate(), balanceAfter, null, withdrawalId);
    }

    /** Credit-back of a failed withdrawal (refunds the earlier debit). */
    public static WalletLedgerEntry withdrawalReversal(UUID userId, CryptoAsset asset, BigDecimal amount,
            BigDecimal balanceAfter, UUID withdrawalId) {
        return new WalletLedgerEntry(userId, asset, WalletLedgerType.WITHDRAWAL_REVERSAL,
                amount, balanceAfter, null, withdrawalId);
    }

    /** Off-chain debit for a real-money tournament buy-in. {@code idempotencyKey} is stored in the unique
     *  external-tx column so a repeated buy-in for the same entry is rejected. */
    public static WalletLedgerEntry tournamentBuyIn(UUID userId, CryptoAsset asset, BigDecimal amount,
            BigDecimal balanceAfter, String idempotencyKey) {
        return new WalletLedgerEntry(userId, asset, WalletLedgerType.TOURNAMENT_BUYIN,
                amount.negate(), balanceAfter, idempotencyKey, null);
    }

    /** Off-chain credit for a real-money tournament prize/payout (idempotent via {@code idempotencyKey}). */
    public static WalletLedgerEntry tournamentPayout(UUID userId, CryptoAsset asset, BigDecimal amount,
            BigDecimal balanceAfter, String idempotencyKey) {
        return new WalletLedgerEntry(userId, asset, WalletLedgerType.TOURNAMENT_PAYOUT,
                amount, balanceAfter, idempotencyKey, null);
    }

    /** Off-chain credit-back of a buy-in when a real-money tournament is cancelled (idempotent). */
    public static WalletLedgerEntry tournamentRefund(UUID userId, CryptoAsset asset, BigDecimal amount,
            BigDecimal balanceAfter, String idempotencyKey) {
        return new WalletLedgerEntry(userId, asset, WalletLedgerType.TOURNAMENT_REFUND,
                amount, balanceAfter, idempotencyKey, null);
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
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

    public WalletLedgerType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public String getExternalTxId() {
        return externalTxId;
    }

    public UUID getWithdrawalId() {
        return withdrawalId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
