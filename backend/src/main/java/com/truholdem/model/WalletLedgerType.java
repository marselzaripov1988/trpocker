package com.truholdem.model;

/** Type of an append-only wallet ledger entry. */
public enum WalletLedgerType {
    DEPOSIT,
    WITHDRAWAL,
    WITHDRAWAL_REVERSAL,
    /** Off-chain debit: real-money tournament entry (buy-in). */
    TOURNAMENT_BUYIN,
    /** Off-chain credit: real-money tournament prize/payout. */
    TOURNAMENT_PAYOUT,
    /** Off-chain credit: buy-in returned when a real-money tournament is cancelled (e.g. under-filled). */
    TOURNAMENT_REFUND
}
