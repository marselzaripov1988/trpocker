package com.truholdem.domain.aggregate;

import com.truholdem.domain.event.DomainEvent;
import com.truholdem.domain.event.HandCompleted;
import com.truholdem.domain.value.Chips;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box invariants on the aggregate's showdown and side-pot distribution. The deck is
 * shuffled randomly, so these assert properties that must hold for ANY board (chip conservation,
 * winner metadata, pot-result events) rather than specific winners.
 */
@DisplayName("PokerGame Aggregate — showdown & side pots")
class PokerGameShowdownTest {

    private static final Chips SMALL_BLIND = Chips.of(10);
    private static final Chips BIG_BLIND = Chips.of(20);

    private static int totalChips(PokerGame game) {
        return game.getPlayers().stream().mapToInt(Player::getChips).sum();
    }

    private static void playCheckCallToShowdown(PokerGame game) {
        int guard = 0;
        while (game.getPhase() != GamePhase.FINISHED && guard++ < 200) {
            Player current = game.getCurrentPlayer();
            if (current == null) {
                break;
            }
            if (current.getBetAmount() < game.getCurrentBet().amount()) {
                game.executeAction(current.getId(), PlayerAction.CALL, null);
            } else {
                game.executeAction(current.getId(), PlayerAction.CHECK, null);
            }
        }
    }

    private static HandCompleted lastHandCompleted(PokerGame game) {
        return game.getDomainEvents().stream()
                .filter(e -> e instanceof HandCompleted)
                .map(e -> (HandCompleted) e)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    @Nested
    @DisplayName("Chip conservation")
    class ChipConservation {

        @Test
        @DisplayName("total chips are preserved through a checked-down showdown")
        void conservedThroughShowdown() {
            PokerGame game = PokerGame.create(List.of(
                    new PlayerInfo("Alice", 1000, false),
                    new PlayerInfo("Bob", 1000, false)), SMALL_BLIND, BIG_BLIND);
            game.startNewHand();

            playCheckCallToShowdown(game);

            assertEquals(GamePhase.FINISHED, game.getPhase());
            assertEquals(2000, totalChips(game), "chips must be zero-sum across the hand");
        }

        @Test
        @DisplayName("total chips are preserved when three unequal stacks go all-in (side pots)")
        void conservedWithSidePots() {
            PokerGame game = PokerGame.create(List.of(
                    new PlayerInfo("Alice", 300, false),
                    new PlayerInfo("Bob", 500, false),
                    new PlayerInfo("Charlie", 1000, false)), SMALL_BLIND, BIG_BLIND);
            game.startNewHand();

            int guard = 0;
            while (game.getPhase() != GamePhase.FINISHED && guard++ < 200) {
                Player current = game.getCurrentPlayer();
                if (current == null) {
                    break;
                }
                game.executeAction(current.getId(), PlayerAction.ALL_IN, null);
            }

            assertEquals(GamePhase.FINISHED, game.getPhase());
            assertEquals(1800, totalChips(game), "side-pot distribution must remain zero-sum");
        }
    }

    @Nested
    @DisplayName("Result metadata")
    class ResultMetadata {

        @Test
        @DisplayName("a showdown records winner metadata and pot results")
        void showdownRecordsMetadata() {
            PokerGame game = PokerGame.create(List.of(
                    new PlayerInfo("Alice", 1000, false),
                    new PlayerInfo("Bob", 1000, false)), SMALL_BLIND, BIG_BLIND);
            game.startNewHand();

            playCheckCallToShowdown(game);

            assertNotNull(game.getWinnerName());
            assertFalse(game.getWinnerIds().isEmpty());
            assertNotNull(game.getWinningHandDescription());

            HandCompleted completed = lastHandCompleted(game);
            assertNotNull(completed);
            assertTrue(completed.wentToShowdown());
            assertFalse(completed.getPotResults().isEmpty());
        }

        @Test
        @DisplayName("a fold-out win awards the pot with no showdown description")
        void foldOutWin() {
            PokerGame game = PokerGame.create(List.of(
                    new PlayerInfo("Alice", 1000, false),
                    new PlayerInfo("Bob", 1000, false)), SMALL_BLIND, BIG_BLIND);
            game.startNewHand();

            Player firstToAct = game.getCurrentPlayer();
            game.executeAction(firstToAct.getId(), PlayerAction.FOLD, null);

            assertEquals(GamePhase.FINISHED, game.getPhase());
            assertEquals(2000, totalChips(game));
            assertNotNull(game.getWinnerName());
            assertEquals(1, game.getWinnerIds().size());
            assertNull(game.getWinningHandDescription(), "fold-out wins have no hand description");

            HandCompleted completed = lastHandCompleted(game);
            assertNotNull(completed);
            assertFalse(completed.wentToShowdown());
        }
    }

    @Nested
    @DisplayName("Events")
    class Events {

        @Test
        @DisplayName("HandCompleted is emitted exactly once per hand")
        void singleHandCompleted() {
            PokerGame game = PokerGame.create(List.of(
                    new PlayerInfo("Alice", 1000, false),
                    new PlayerInfo("Bob", 1000, false)), SMALL_BLIND, BIG_BLIND);
            game.startNewHand();
            game.clearDomainEvents();

            playCheckCallToShowdown(game);

            long completedCount = game.getDomainEvents().stream()
                    .filter(e -> e instanceof HandCompleted)
                    .count();
            assertEquals(1, completedCount);
        }

        @Test
        @DisplayName("PotAwarded events are emitted at showdown")
        void potAwardedEmitted() {
            PokerGame game = PokerGame.create(List.of(
                    new PlayerInfo("Alice", 1000, false),
                    new PlayerInfo("Bob", 1000, false)), SMALL_BLIND, BIG_BLIND);
            game.startNewHand();
            game.clearDomainEvents();

            playCheckCallToShowdown(game);

            List<DomainEvent> events = game.getDomainEvents();
            assertTrue(events.stream().anyMatch(e -> e instanceof com.truholdem.domain.event.PotAwarded));
        }
    }
}
