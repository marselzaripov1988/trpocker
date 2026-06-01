package com.truholdem.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.truholdem.dto.AchievementResponse;
import com.truholdem.dto.PlayerAchievementResponse;
import com.truholdem.model.Achievement;
import com.truholdem.model.PlayerAchievement;
import com.truholdem.model.PlayerStatistics;

/**
 * Pins that {@link AchievementResponse} and {@link PlayerAchievementResponse} serialize identically to
 * the {@link Achievement}/{@link PlayerAchievement} entities (Phase 6 decoupling, shape-preserving).
 */
@DisplayName("Achievement JSON wire-format contract")
class AchievementJsonContractTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private Achievement sampleAchievement() {
        Achievement a = new Achievement("FIRST_WIN", "First Win", "Win your first hand",
                "trophy", "BEGINNER", 10, "HANDS_WON", 1);
        a.setId(UUID.fromString("00000000-0000-0000-0000-0000000000c3"));
        return a;
    }

    @Test
    @DisplayName("AchievementResponse serializes identically to the Achievement entity")
    void achievementMatchesEntityJson() {
        Achievement entity = sampleAchievement();
        assertEquals(mapper.valueToTree(entity), mapper.valueToTree(AchievementResponse.from(entity)));
    }

    @Test
    @DisplayName("isHidden() serializes as 'hidden' in the DTO too")
    void hiddenFieldName() {
        JsonNode json = mapper.valueToTree(AchievementResponse.from(sampleAchievement()));
        assertTrue(json.has("hidden"));
    }

    @Test
    @DisplayName("PlayerAchievementResponse serializes identically (incl. nested stats + achievement)")
    void playerAchievementMatchesEntityJson() {
        PlayerStatistics stats = new PlayerStatistics("Hero");
        stats.setId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        stats.setHandsPlayed(10);
        stats.setHandsWon(4);

        PlayerAchievement pa = new PlayerAchievement(stats, sampleAchievement());
        pa.setId(UUID.fromString("00000000-0000-0000-0000-0000000000d4"));
        pa.setUnlockedAt(LocalDateTime.of(2026, 6, 1, 12, 0));
        pa.setProgress(1);

        assertEquals(mapper.valueToTree(pa), mapper.valueToTree(PlayerAchievementResponse.from(pa)));
    }
}
