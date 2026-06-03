package com.truholdem.model;

/** Lifecycle of a pre-generated deposit address in the offline pool. */
public enum DepositAddressStatus {
    /** Imported, not yet handed to any user. */
    FREE,
    /** Handed out to a user (one per user per asset); the address now belongs to that user. */
    ASSIGNED
}
