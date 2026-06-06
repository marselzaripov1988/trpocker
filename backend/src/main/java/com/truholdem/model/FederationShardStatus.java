package com.truholdem.model;

/** Lifecycle of one shard within a federated pyramid (a 10,000-player sub-pyramid run to a single winner). */
public enum FederationShardStatus {
    /** Allocated but not yet open for fill (waiting its turn in the wave). */
    PENDING,
    /** Filling toward its capacity; starts (its child pyramid tournament) when full. */
    REGISTERING,
    /** Filled to capacity, waiting for a concurrency slot before its child pyramid is materialized. */
    READY,
    /** Buy-up variant: the child pyramid is materialized + seated and open for seat buy-outs before it starts. */
    BUYUP_OPEN,
    /** The shard's pyramid is running. */
    RUNNING,
    /** The shard produced its winner. */
    COMPLETED,
    /** Cancelled (e.g. under-filled past its deadline, or the federation was cancelled). */
    CANCELLED
}
