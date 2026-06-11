package com.truholdem.model;

/** Lifecycle of an admin-approved refund of a funded buy-in from a dedicated player wallet. Mirrors the
 *  withdrawal state machine: nothing is signed/broadcast until a moderator approves. */
public enum FederationRefundStatus {
    /** Created, awaiting moderator approval (and the refund destination address). */
    PENDING_APPROVAL,
    /** Approved by a moderator (destination set) — ready for the offline signer. */
    APPROVED,
    /** The offline-signed refund tx was broadcast, awaiting confirmation. */
    BROADCAST,
    /** Confirmed on-chain — the player received the net amount. */
    CONFIRMED,
    /** Failed on-chain. */
    FAILED,
    /** Rejected by a moderator. */
    REJECTED
}
