package com.truholdem.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import com.truholdem.model.PlayerStatistics;

/**
 * Read-model for player statistics (Phase 6, CQRS read side). Decouples the REST API from the
 * {@link PlayerStatistics} JPA entity while serializing to the exact same JSON the frontend consumes:
 * the stored fields <em>and</em> the computed getters (VPIP, PFR, win rate, …) that Jackson emits today.
 */
public record PlayerStatisticsResponse(
        UUID id,
        UUID userId,
        String playerName,
        int handsPlayed,
        int handsWon,
        BigDecimal totalWinnings,
        BigDecimal totalLosses,
        int biggestPotWon,
        int handsVoluntarilyPutInPot,
        int handsRaisedPreFlop,
        int totalBets,
        int totalRaises,
        int totalCalls,
        int totalFolds,
        int totalChecks,
        int handsWentToShowdown,
        int showdownsWon,
        int timesAllIn,
        int allInsWon,
        int currentWinStreak,
        int longestWinStreak,
        int currentLoseStreak,
        int longestLoseStreak,
        LocalDateTime firstHandPlayed,
        LocalDateTime lastHandPlayed,
        int totalSessions,
        // computed (mirror the entity's derived getters so the JSON shape is unchanged).
        // Jackson lowercases the acronym getters: getVPIP()->vpip, getPFR()->pfr, getWTSD()->wtsd.
        double vpip,
        double pfr,
        double aggressionFactor,
        double wtsd,
        double wonAtShowdown,
        double winRate,
        BigDecimal netProfit,
        BigDecimal averageProfit,
        double allInWinRate,
        double foldPercentage) {

    public static PlayerStatisticsResponse from(PlayerStatistics s) {
        return new PlayerStatisticsResponse(
                s.getId(),
                s.getUserId(),
                s.getPlayerName(),
                s.getHandsPlayed(),
                s.getHandsWon(),
                s.getTotalWinnings(),
                s.getTotalLosses(),
                s.getBiggestPotWon(),
                s.getHandsVoluntarilyPutInPot(),
                s.getHandsRaisedPreFlop(),
                s.getTotalBets(),
                s.getTotalRaises(),
                s.getTotalCalls(),
                s.getTotalFolds(),
                s.getTotalChecks(),
                s.getHandsWentToShowdown(),
                s.getShowdownsWon(),
                s.getTimesAllIn(),
                s.getAllInsWon(),
                s.getCurrentWinStreak(),
                s.getLongestWinStreak(),
                s.getCurrentLoseStreak(),
                s.getLongestLoseStreak(),
                s.getFirstHandPlayed(),
                s.getLastHandPlayed(),
                s.getTotalSessions(),
                s.getVPIP(),
                s.getPFR(),
                s.getAggressionFactor(),
                s.getWTSD(),
                s.getWonAtShowdown(),
                s.getWinRate(),
                s.getNetProfit(),
                s.getAverageProfit(),
                s.getAllInWinRate(),
                s.getFoldPercentage());
    }
}
