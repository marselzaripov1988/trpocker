package com.truholdem.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/** Body to schedule a tournament's automatic start time (ISO-8601 instant). */
public record ScheduleTournamentRequest(@NotNull Instant startAt) {
}
