package com.truholdem.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Computes the absolute start instant for a tournament pinned to a time of day. The slot must leave at least
 * {@code runway} of registration time: if today's occurrence of {@code timeOfDay} is already in the past or
 * closer than the runway, the next day's occurrence is used. Pure (takes {@code now}) so it is deterministic
 * and testable.
 */
public final class TournamentSlotPlanner {

    private TournamentSlotPlanner() {
    }

    public static Instant nextSlotAtLeastRunwayAway(LocalTime timeOfDay, ZoneId zone, Duration runway,
            Instant now) {
        ZonedDateTime nowZ = now.atZone(zone);
        ZonedDateTime slot = nowZ.toLocalDate().atTime(timeOfDay).atZone(zone);
        if (!slot.toInstant().isAfter(now.plus(runway))) {
            slot = slot.plusDays(1);
        }
        return slot.toInstant();
    }
}
