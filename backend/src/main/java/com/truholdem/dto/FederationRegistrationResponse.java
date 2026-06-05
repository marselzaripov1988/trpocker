package com.truholdem.dto;

import java.util.UUID;

import com.truholdem.model.FederationShardStatus;
import com.truholdem.model.FederationStatus;

/** Confirmation of a player's registration into a federated pyramid: which shard they landed in. */
public record FederationRegistrationResponse(
        UUID federationId,
        UUID playerId,
        int shardIndex,
        FederationShardStatus shardStatus,
        FederationStatus federationStatus) {
}
