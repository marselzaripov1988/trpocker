package com.truholdem.model;

/** Lifecycle of a per-player dedicated tournament wallet (isolated-custody federated pyramid). */
public enum FederationWalletStatus {
    /** Imported (offline-generated), not yet handed to a player. */
    FREE,
    /** Handed to a player as their dedicated buy-in deposit address; awaiting the on-chain deposit. */
    ASSIGNED,
    /** The buy-in deposit has landed and confirmed on-chain — the player is paid in. */
    FUNDED,
    /** The buy-in was refunded on-chain back to the player (federation cancelled / un-registered). */
    REFUNDED
}
