package com.truholdem.dto;

import java.util.UUID;

import com.truholdem.model.FederationShardStatus;
import com.truholdem.model.FederationStatus;

/**
 * Confirmation of a player's registration into a federated pyramid: which shard they landed in. For an
 * isolated-custody federation the player is not yet seated ({@code shardIndex == -1}, {@code shardStatus} null)
 * and {@code depositAddress} is the dedicated wallet to pay the buy-in into on-chain.
 */
public record FederationRegistrationResponse(
        UUID federationId,
        UUID playerId,
        int shardIndex,
        FederationShardStatus shardStatus,
        FederationStatus federationStatus,
        String depositAddress) {
}
