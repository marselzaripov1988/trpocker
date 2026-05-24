package com.truholdem.dto;

import com.truholdem.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


public record TournamentDetailResponse(
    UUID id,
    String name,
    TournamentType type,
    TournamentStatus status,
    
    
    int registeredPlayers,
    int playersRemaining,
    int minPlayers,
    int maxPlayers,
    
    
    int currentLevel,
    BlindLevelInfo currentBlinds,
    BlindLevelInfo nextBlinds,
    long secondsToNextLevel,
    
    
    int startingChips,
    int averageStack,
    int chipLeaderStack,
    String chipLeaderName,
    
    
    int buyIn,
    int prizePool,
    List<Integer> payoutStructure,
    int paidPositions,
    
    
    int tableCount,
    List<TableSummary> tables,
    
    
    Instant createdAt,
    Instant startTime,
    String duration
) {
    
  /** @deprecated Prefer {@link #fromSummary} for large fields to avoid loading all registrations. */
    @Deprecated
    public static TournamentDetailResponse from(Tournament tournament) {
        return fromSummary(
                tournament,
                tournament.getRegistrations().size(),
                tournament.getPlayersRemaining(),
                tournament.getActiveTables().size(),
                tournament.getActiveTables().stream().map(TableSummary::from).toList(),
                tournament.getChipLeader().map(TournamentRegistration::getPlayerName).orElse(null),
                tournament.getChipLeader().map(TournamentRegistration::getCurrentChips).orElse(0),
                tournament.getAverageStack(),
                tournament.getPrizePool());
    }

    /**
     * Builds a detail view using aggregate counts (no need to load full registration/table lists).
     */
    public static TournamentDetailResponse fromSummary(
            Tournament tournament,
            int registeredPlayers,
            int playersRemaining,
            int tableCount,
            List<TableSummary> tables,
            String chipLeaderName,
            int chipLeaderStack,
            int averageStack,
            int prizePool) {
        BlindLevel current = tournament.getCurrentBlindLevel();
        BlindLevel next = tournament.getBlindStructure().getLevelAt(tournament.getCurrentLevel() + 1);

        long secondsToNext = 0;
        if (tournament.getLevelStartTime() != null && tournament.getStatus().isPlayable()) {
            long elapsed = Duration.between(tournament.getLevelStartTime(), Instant.now()).toSeconds();
            long levelDuration = tournament.getBlindStructure().getLevelDurationMinutes() * 60L;
            secondsToNext = Math.max(0, levelDuration - elapsed);
        }

        String durationStr = null;
        if (tournament.getStartTime() != null) {
            Duration d = Duration.between(tournament.getStartTime(), Instant.now());
            durationStr = String.format("%d:%02d:%02d", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
        }

        return new TournamentDetailResponse(
                tournament.getId(),
                tournament.getName(),
                tournament.getTournamentType(),
                tournament.getStatus(),
                registeredPlayers,
                playersRemaining,
                tournament.getMinPlayers(),
                tournament.getMaxPlayers(),
                tournament.getCurrentLevel(),
                BlindLevelInfo.from(current, tournament.getCurrentLevel()),
                BlindLevelInfo.from(next, tournament.getCurrentLevel() + 1),
                secondsToNext,
                tournament.getStartingChips(),
                averageStack,
                chipLeaderStack,
                chipLeaderName,
                tournament.getBuyIn(),
                prizePool,
                tournament.getPayoutStructure(),
                tournament.getPaidPositions(),
                tableCount,
                tables,
                tournament.getCreatedAt(),
                tournament.getStartTime(),
                durationStr);
    }
    
    
    public record BlindLevelInfo(
        int level,
        int smallBlind,
        int bigBlind,
        int ante
    ) {
        public static BlindLevelInfo from(BlindLevel level, int levelNumber) {
            return new BlindLevelInfo(
                levelNumber,
                level.getSmallBlind(),
                level.getBigBlind(),
                level.getAnte()
            );
        }
    }
    
    
    public record TableSummary(
        UUID id,
        int tableNumber,
        int playerCount,
        boolean isFinalTable
    ) {
        public static TableSummary from(TournamentTable table) {
            return new TableSummary(
                table.getId(),
                table.getTableNumber(),
                table.getPlayerCount(),
                table.isFinalTable()
            );
        }
    }
}
