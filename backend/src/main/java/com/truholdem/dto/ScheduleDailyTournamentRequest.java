package com.truholdem.dto;

import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;

/**
 * Body to pin a tournament to a time-of-day slot. {@code timeOfDay} (e.g. "20:00") is interpreted in the
 * configured zone; the first slot leaves at least the configured registration runway (else the next day's
 * slot). {@code requireFull} = start only when the table is full at the slot, otherwise postpone a day.
 */
public record ScheduleDailyTournamentRequest(@NotNull LocalTime timeOfDay, boolean requireFull) {
}
