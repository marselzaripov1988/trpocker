package com.truholdem.model;

/**
 * Lifecycle of a federated pyramid tournament: players register, shards fill and run in waves, the field
 * narrows to one finalist per shard, then (once all shards are done) an admin schedules and runs the final.
 */
public enum FederationStatus {
    /** Accepting registrations; shards fill in order and start when full (wave model). */
    REGISTERING,
    /** At least one shard is running; still gathering shard winners. */
    SHARDS_RUNNING,
    /** All shards complete; the full set of finalists is gathered, awaiting the final's scheduled start. */
    AWAITING_FINAL,
    /** An admin has set the final start time (finalists notified). */
    FINAL_SCHEDULED,
    /** The final pyramid among the shard winners is running. */
    FINAL_RUNNING,
    /** A grand champion has been crowned. */
    COMPLETED,
    /** Cancelled before completion. */
    CANCELLED
}
