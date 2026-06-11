package com.truholdem.model;

/** Lifecycle of a deposit→treasury consolidation (sweep) transaction. Mirrors the withdrawal flow minus the
 *  moderator gate — a sweep is an internal custody move, not a user payout. */
public enum SweepBatchStatus {
    /** Planned + assembled (UTXOs selected), awaiting the offline signature + broadcast. */
    PLANNED,
    /** Broadcast on-chain, awaiting confirmation. */
    BROADCAST,
    /** Confirmed on-chain — funds are consolidated in the treasury. */
    CONFIRMED,
    /** Failed or reverted on-chain. */
    FAILED
}
