package com.truholdem.model;

/**
 * Lifecycle of a withdrawal request. A request is only created once KYC has passed and funds are debited;
 * it then moves through broadcasting to on-chain confirmation (or failure → reversal).
 */
public enum WithdrawalStatus {
    /** Debited and awaiting manual moderator approval (only when withdrawal-approval-required is on). */
    PENDING_APPROVAL,
    APPROVED,
    BROADCAST,
    CONFIRMED,
    FAILED,
    /** Moderator rejected the request; the debit was reversed. */
    REJECTED
}
