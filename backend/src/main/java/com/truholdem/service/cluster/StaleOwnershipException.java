package com.truholdem.service.cluster;

import java.util.UUID;

/**
 * Thrown when a fenced write is rejected because this node is no longer the up-to-date owner of the
 * table — another node has since taken the lease and bumped the fencing token (e.g. this node was paused
 * by a long GC while its lease expired). The mutation is aborted so the stale owner cannot clobber the
 * authoritative state the new owner is now managing.
 */
public class StaleOwnershipException extends RuntimeException {

    public StaleOwnershipException(UUID gameId) {
        super("Fenced write rejected for table " + gameId + " — ownership has moved to another node");
    }
}
