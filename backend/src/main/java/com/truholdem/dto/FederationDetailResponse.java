package com.truholdem.dto;

import java.time.Instant;
import java.util.UUID;

import com.truholdem.model.FederationStatus;

/** Read view of a federated pyramid: config, lifecycle status, per-shard-status counts and the champion. */
public record FederationDetailResponse(
        UUID id,
        String name,
        FederationStatus status,
        int shardSize,
        int shardCount,
        int seatsPerTable,
        long registeredPlayers,
        Instant registrationDeadline,
        Instant finalScheduledStart,
        UUID finalTournamentId,
        UUID championPlayerId,
        int feeBasisPoints,
        int shardsPending,
        int shardsRegistering,
        int shardsReady,
        int shardsRunning,
        int shardsCompleted) {
}
