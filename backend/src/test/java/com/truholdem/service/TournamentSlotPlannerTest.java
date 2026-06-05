package com.truholdem.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TournamentSlotPlanner (time-of-day slot with registration runway)")
class TournamentSlotPlannerTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Duration RUNWAY = Duration.ofHours(3);
    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);

    private Instant plan(Instant now) {
        return TournamentSlotPlanner.nextSlotAtLeastRunwayAway(EIGHT_PM, UTC, RUNWAY, now);
    }

    @Test
    @DisplayName("today's slot is used when it is more than the runway away")
    void todayWhenEnoughRunway() {
        assertThat(plan(Instant.parse("2024-01-01T10:00:00Z")))
                .isEqualTo(Instant.parse("2024-01-01T20:00:00Z"));
    }

    @Test
    @DisplayName("next day's slot when today's is closer than the runway")
    void tomorrowWhenWithinRunway() {
        assertThat(plan(Instant.parse("2024-01-01T18:30:00Z")))
                .isEqualTo(Instant.parse("2024-01-02T20:00:00Z"));
    }

    @Test
    @DisplayName("next day's slot when today's has already passed")
    void tomorrowWhenPassed() {
        assertThat(plan(Instant.parse("2024-01-01T21:00:00Z")))
                .isEqualTo(Instant.parse("2024-01-02T20:00:00Z"));
    }

    @Test
    @DisplayName("exactly the runway boundary rolls to the next day (strict runway)")
    void boundaryRollsToNextDay() {
        assertThat(plan(Instant.parse("2024-01-01T17:00:00Z")))
                .isEqualTo(Instant.parse("2024-01-02T20:00:00Z"));
    }

    @Test
    @DisplayName("zone is honoured: 20:00 New York is 5h behind UTC")
    void zoneHonoured() {
        Instant slot = TournamentSlotPlanner.nextSlotAtLeastRunwayAway(
                EIGHT_PM, ZoneId.of("America/New_York"), RUNWAY, Instant.parse("2024-01-01T10:00:00Z"));
        // 2024-01-01 20:00 EST = 2024-01-02 01:00 UTC
        assertThat(slot).isEqualTo(Instant.parse("2024-01-02T01:00:00Z"));
    }
}
