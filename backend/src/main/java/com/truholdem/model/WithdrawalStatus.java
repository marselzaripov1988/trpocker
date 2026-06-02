package com.truholdem.model;

/**
 * Lifecycle of a withdrawal request. A request is only created once KYC has passed and funds are debited;
 * it then moves through broadcasting to on-chain confirmation (or failure → reversal).
 */
public enum WithdrawalStatus {
    APPROVED,
    BROADCAST,
    CONFIRMED,
    FAILED
}
