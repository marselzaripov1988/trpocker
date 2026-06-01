package com.truholdem.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.truholdem.dto.PlayerStatisticsResponse;
import com.truholdem.model.PlayerStatistics;

/**
 * Pins that {@link PlayerStatisticsResponse} serializes to JSON identical to the {@link PlayerStatistics}
 * entity (Phase 6 decoupling), including the computed metrics (VPIP/PFR/winRate/…) Jackson emits today.
 */
@DisplayName("PlayerStatistics JSON wire-format contract")
class PlayerStatisticsJsonContractTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private PlayerStatistics sample() {
        PlayerStatistics s = new PlayerStatistics("Hero");
        s.setId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        s.setUserId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"));
        s.setHandsPlayed(100);
        s.setHandsWon(30);
        s.setTotalWinnings(BigDecimal.valueOf(5000));
        s.setTotalLosses(BigDecimal.valueOf(2000));
        s.setBiggestPotWon(900);
        s.setHandsVoluntarilyPutInPot(42);
        s.setHandsRaisedPreFlop(20);
        s.setTotalBets(15);
        s.setTotalRaises(25);
        s.setTotalCalls(40);
        s.setTotalFolds(50);
        s.setTotalChecks(30);
        s.setHandsWentToShowdown(18);
        s.setShowdownsWon(11);
        s.setTimesAllIn(6);
        s.setAllInsWon(4);
        s.setCurrentWinStreak(3);
        s.setLongestWinStreak(7);
        s.setCurrentLoseStreak(0);
        s.setLongestLoseStreak(4);
        s.setFirstHandPlayed(LocalDateTime.of(2026, 1, 1, 10, 0));
        s.setLastHandPlayed(LocalDateTime.of(2026, 6, 1, 12, 0));
        s.setTotalSessions(9);
        return s;
    }

    @Test
    @DisplayName("PlayerStatisticsResponse serializes identically to the entity (incl. computed metrics)")
    void responseMatchesEntityJson() {
        PlayerStatistics entity = sample();
        JsonNode entityJson = mapper.valueToTree(entity);
        JsonNode dtoJson = mapper.valueToTree(PlayerStatisticsResponse.from(entity));
        assertEquals(entityJson, dtoJson);
    }

    @Test
    @DisplayName("computed metrics keep their JSON names (VPIP, PFR, WTSD, winRate, netProfit)")
    void computedFieldNamesAreStable() {
        JsonNode json = mapper.valueToTree(PlayerStatisticsResponse.from(sample()));
        for (String field : new String[] {"vpip", "pfr", "wtsd", "winRate", "aggressionFactor",
                "wonAtShowdown", "netProfit", "averageProfit", "allInWinRate", "foldPercentage"}) {
            assertTrue(json.has(field), "missing computed field: " + field);
        }
        assertEquals(30.0, json.get("winRate").asDouble(), 0.0001);
    }
}
