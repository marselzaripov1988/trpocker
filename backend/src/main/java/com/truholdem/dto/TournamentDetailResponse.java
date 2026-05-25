package com.truholdem.dto;

import com.truholdem.model.*;

import com.truholdem.service.tournament.TournamentTimingService;

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
    /** Epoch millis when the current blind level ends; null if not running. */
    Long levelEndTimeEpochMillis,
    /** Length of one blind level in seconds (for UI countdown). */
    int levelDurationSeconds,
    
    
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

    /** Player standings (empty when field is too large; use leaderboard API). */
    List<LeaderboardEntryDto> players,
    
    
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
                tournament.getActiveTables().stream()
                        .map(TableSummary::from)
                        .toList(),
                tournament.getChipLeader().map(TournamentRegistration::getPlayerName).orElse(null),
                tournament.getChipLeader().map(TournamentRegistration::getCurrentChips).orElse(0),
                tournament.getAverageStack(),
                tournament.getPrizePool(),
                null,
                List.of(),
                List.of());
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
            int prizePool,
            TournamentTimingService timingService,
            List<LeaderboardEntryDto> players,
            List<TableSummary> tablesWithPlayers) {
        BlindLevel current = tournament.getCurrentBlindLevel();
        BlindLevel next = tournament.getBlindStructure().getLevelAt(tournament.getCurrentLevel() + 1);

        long secondsToNext = timingService != null
                ? timingService.secondsToNextLevel(tournament)
                : 0;
        Long levelEndEpoch = null;
        int levelDurationSeconds = tournament.getBlindStructure().getLevelDurationMinutes() * 60;
        if (timingService != null) {
            levelDurationSeconds = (int) timingService.levelDuration(tournament).toSeconds();
            Instant levelEnd = timingService.levelEndTime(tournament);
            if (levelEnd != null) {
                levelEndEpoch = levelEnd.toEpochMilli();
            }
        } else if (tournament.getLevelStartTime() != null && tournament.getStatus().isPlayable()) {
            long elapsed = Duration.between(tournament.getLevelStartTime(), Instant.now()).toSeconds();
            long levelDuration = tournament.getBlindStructure().getLevelDurationMinutes() * 60L;
            secondsToNext = Math.max(0, levelDuration - elapsed);
            levelEndEpoch = tournament.getLevelStartTime().plusSeconds(levelDuration).toEpochMilli();
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
                levelEndEpoch,
                levelDurationSeconds,
                tournament.getStartingChips(),
                averageStack,
                chipLeaderStack,
                chipLeaderName,
                tournament.getBuyIn(),
                prizePool,
                tournament.getPayoutStructure(),
                tournament.getPaidPositions(),
                tableCount,
                tablesWithPlayers,
                players != null ? players : List.of(),
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
        boolean isFinalTable,
        UUID currentGameId,
        List<TablePlayerSummary> players
    ) {
        public static TableSummary from(TournamentTable table) {
            return from(table, List.of());
        }

        public static TableSummary from(TournamentTable table, List<TablePlayerSummary> players) {
            UUID gameId = table.getCurrentGame() != null ? table.getCurrentGame().getId() : null;
            return new TableSummary(
                table.getId(),
                table.getTableNumber(),
                table.getPlayerCount(),
                table.isFinalTable(),
                gameId,
                players != null ? players : List.of());
        }
    }

    public record TablePlayerSummary(
        UUID id,
        String name,
        int chips,
        boolean isBot
    ) {
        public static TablePlayerSummary from(TournamentRegistration registration) {
            String name = registration.getPlayerName();
            return new TablePlayerSummary(
                    registration.getPlayerId(),
                    name,
                    registration.getCurrentChips(),
                    name != null && name.regionMatches(true, 0, "bot", 0, 3));
        }
    }
}
