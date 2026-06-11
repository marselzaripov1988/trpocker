package com.truholdem.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.truholdem.model.CryptoAsset;
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
        int shardsCompleted,
        /** Effective prize config (the federation's snapshot, or the global default): shard-winner qualifier in
         *  ppm, the non-champion final-table place shares (CSV of bps), and the rest-of-table bps. */
        int shardWinnerPpm,
        String finalTablePlaceBps,
        int finalTableRestBps,
        /** Real-money config: the per-player buy-in and its asset, so the UI can show prize amounts in currency
         *  off the guaranteed pool ({@code shardCount × shardSize × buyIn}). Null/absent for play-money. */
        BigDecimal cryptoBuyInAmount,
        CryptoAsset cryptoBuyInAsset,
        /** Isolated-custody variant: the buy-in is paid on-chain into a dedicated per-player wallet. */
        boolean isolatedWalletsEnabled) {
}
