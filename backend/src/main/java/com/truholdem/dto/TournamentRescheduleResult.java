package com.truholdem.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of an admin postponing an under-filled tournament's start: enough to drive the registrant
 * notification (who, which tournament, the old vs. new slot) without re-querying the tournament.
 */
public record TournamentRescheduleResult(
        UUID tournamentId,
        String tournamentName,
        Instant previousStart,
        Instant newStart) {
}
