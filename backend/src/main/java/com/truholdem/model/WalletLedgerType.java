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
    TOURNAMENT_REFUND,
    /** Off-chain debit: sitting down at a real-money cash (ring) table (buy-in into a stack). */
    CASH_BUYIN,
    /** Off-chain credit: standing up from a real-money cash table (remaining stack returned to the wallet). */
    CASH_CASHOUT
}
