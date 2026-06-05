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
    String duration,

    /** Scheduled auto-start time (null = manual). */
    Instant scheduledStart,
    /** When true, a scheduled tournament only starts at its slot if the table is full (else postpones a day). */
    boolean requireFullToStart
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
                calculateSecondsToNextLevel(tournament),
                calculateLevelEndEpochMillis(tournament),
                tournament.getBlindStructure().getLevelDurationMinutes() * 60,
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
            long secondsToNext,
            Long levelEndEpoch,
            int levelDurationSeconds,
            List<LeaderboardEntryDto> players,
            List<TableSummary> tablesWithPlayers) {
        BlindLevel current = tournament.getCurrentBlindLevel();
        BlindLevel next = tournament.getBlindStructure().getLevelAt(tournament.getCurrentLevel() + 1);

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
                durationStr,
                tournament.getScheduledStart(),
                tournament.isRequireFullToStart());
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

    private static long calculateSecondsToNextLevel(Tournament tournament) {
        if (tournament.getLevelStartTime() == null || !tournament.getStatus().isPlayable()) {
            return 0;
        }
        long elapsed = Duration.between(tournament.getLevelStartTime(), Instant.now()).toSeconds();
        long levelDuration = tournament.getBlindStructure().getLevelDurationMinutes() * 60L;
        return Math.max(0, levelDuration - elapsed);
    }

    private static Long calculateLevelEndEpochMillis(Tournament tournament) {
        if (tournament.getLevelStartTime() == null || !tournament.getStatus().isPlayable()) {
            return null;
        }
        long levelDuration = tournament.getBlindStructure().getLevelDurationMinutes() * 60L;
        return tournament.getLevelStartTime().plusSeconds(levelDuration).toEpochMilli();
    }
}
