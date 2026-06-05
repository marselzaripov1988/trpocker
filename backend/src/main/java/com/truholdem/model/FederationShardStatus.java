package com.truholdem.model;

/** Lifecycle of one shard within a federated pyramid (a 10,000-player sub-pyramid run to a single winner). */
public enum FederationShardStatus {
    /** Allocated but not yet open for fill (waiting its turn in the wave). */
    PENDING,
    /** Filling toward its capacity; starts (its child pyramid tournament) when full. */
    REGISTERING,
    /** The shard's pyramid is running. */
    RUNNING,
    /** The shard produced its winner. */
    COMPLETED,
    /** Cancelled (e.g. under-filled past its deadline, or the federation was cancelled). */
    CANCELLED
}
