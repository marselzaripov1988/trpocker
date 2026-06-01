package com.truholdem.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.truholdem.model.PlayerAchievement;

/**
 * Read-model for a player's unlocked achievement (Phase 6). Mirrors the current serialized shape,
 * including the nested {@code playerStats} (serialized today via open-in-view) and {@code achievement}.
 */
public record PlayerAchievementResponse(
        UUID id,
        PlayerStatisticsResponse playerStats,
        AchievementResponse achievement,
        LocalDateTime unlockedAt,
        int progress) {

    public static PlayerAchievementResponse from(PlayerAchievement pa) {
        return new PlayerAchievementResponse(
                pa.getId(),
                pa.getPlayerStats() == null ? null : PlayerStatisticsResponse.from(pa.getPlayerStats()),
                pa.getAchievement() == null ? null : AchievementResponse.from(pa.getAchievement()),
                pa.getUnlockedAt(),
                pa.getProgress());
    }
}
