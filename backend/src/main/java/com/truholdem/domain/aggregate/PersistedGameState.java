package com.truholdem.domain.aggregate;

import com.truholdem.model.Card;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Snapshot of all in-memory poker state shared between {@link PokerGame} and
 * {@link com.truholdem.model.Game}. Used for reconstitution and mapping without
 * pulling JPA into the aggregate.
 *
 * <p>{@link com.truholdem.model.HandLifecycleState} is intentionally excluded:
 * lifecycle delays are owned by {@code PokerGameService}, not the aggregate.
 */
public record PersistedGameState(
        UUID id,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        int smallBlindAmount,
        int bigBlindAmount,
        GamePhase phase,
        int dealerPosition,
        int currentPlayerIndex,
        int handNumber,
        boolean finished,
        int currentBet,
        int minRaise,
        int lastRaiseAmount,
        int actionsThisRound,
        UUID lastAggressorId,
        int buttonSeatPosition,
        boolean deadButton,
        Map<Integer, Integer> missedBlinds,
        int potAmount,
        List<Player> players,
        List<Card> communityCards,
        List<Card> deck,
        String winnerName,
        String winningHandDescription,
        List<UUID> winnerIds) {
}
