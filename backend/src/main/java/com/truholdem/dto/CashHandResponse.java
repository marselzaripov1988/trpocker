package com.truholdem.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The current hand at a cash table from the requesting player's perspective. {@code yourCards} are the caller's
 * own hole cards (empty when they are not in the hand); other players' cards are never exposed.
 */
public record CashHandResponse(
        boolean inProgress,
        int handNumber,
        String phase,
        BigDecimal pot,
        String currentActorName,
        List<String> communityCards,
        List<String> yourCards) {

    /** No hand is currently being played. */
    public static CashHandResponse idle() {
        return new CashHandResponse(false, 0, null, BigDecimal.ZERO, null, List.of(), List.of());
    }
}
