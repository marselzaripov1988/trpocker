package com.truholdem.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.truholdem.domain.value.Chips;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;

/**
 * Aggregate-side port of {@link com.truholdem.model.GameRulesGoldenTest}. Pins the same
 * official-poker bookkeeping (dead button, missed blinds, last aggressor) on the domain
 * {@link PokerGame} so Phase 1 hand logic stays testable without Spring or the legacy service.
 */
@DisplayName("PokerGame rules — golden safety net (aggregate port)")
class PokerGameRulesGoldenTest {

    private static final Chips SMALL_BLIND = Chips.of(10);
    private static final Chips BIG_BLIND = Chips.of(20);

    private static PokerGame gameWithChips(int... chipsPerSeat) {
        List<PlayerInfo> infos = new java.util.ArrayList<>();
        for (int i = 0; i < chipsPerSeat.length; i++) {
            infos.add(new PlayerInfo("Player" + i, chipsPerSeat[i], false));
        }
        return PokerGame.create(infos, SMALL_BLIND, BIG_BLIND);
    }

    private static void foldOutHand(PokerGame game) {
        int guard = 0;
        while (game.getPhase() != GamePhase.FINISHED && guard++ < 50) {
            Player current = game.getCurrentPlayer();
            if (current == null) {
                break;
            }
            game.executeAction(current.getId(), PlayerAction.FOLD, null);
        }
    }

    /** Reconstitute with a fixed button seat so the next {@link PokerGame#startNewHand()} advances once. */
    private static PokerGame primedForButtonAdvance(PokerGame template, int buttonSeat, int handNumber) {
        int dealerIndex = template.getPlayers().stream()
                .filter(p -> p.getSeatPosition() == buttonSeat)
                .findFirst()
                .map(p -> template.getPlayers().indexOf(p))
                .orElse(buttonSeat);

        PersistedGameState state = new PersistedGameState(
                template.getId(),
                null,
                Instant.now(),
                Instant.now(),
                SMALL_BLIND.amount(),
                BIG_BLIND.amount(),
                GamePhase.FINISHED,
                dealerIndex,
                0,
                handNumber,
                false,
                0,
                BIG_BLIND.amount(),
                BIG_BLIND.amount(),
                0,
                null,
                buttonSeat,
                false,
                Map.of(),
                0,
                template.getPlayers(),
                List.of(),
                List.of(),
                null,
                null,
                List.of());
        return PokerGame.reconstitute(state);
    }

    @Nested
    @DisplayName("Dead button advancement")
    class DeadButton {

        @Test
        @DisplayName("Button moves to the next clockwise seat when it is occupied")
        void advancesToNextOccupiedSeat() {
            PokerGame game = gameWithChips(1000, 1000, 1000);
            game.startNewHand();
            assertEquals(0, game.getButtonSeatPosition());

            foldOutHand(game);
            game.startNewHand();

            assertEquals(1, game.getButtonSeatPosition());
            assertFalse(game.isDeadButton(), "button is live when a player occupies the seat");
            assertEquals(1, game.getDealerPosition());
        }

        @Test
        @DisplayName("Button is dead when the next seat has no eligible player")
        void deadButtonWhenNextSeatEmpty() {
            PokerGame template = gameWithChips(1000, 0, 1000);
            PokerGame game = primedForButtonAdvance(template, 0, 1);

            game.startNewHand();

            assertTrue(game.isDeadButton(), "no eligible player at the next seat => dead button");
            assertEquals(1, game.getButtonSeatPosition(), "button seat still advances clockwise");
            assertEquals(2, game.getDealerPosition());
        }

        @Test
        @DisplayName("Button seat wraps around the table")
        void wrapsAroundTable() {
            PokerGame template = gameWithChips(1000, 1000, 1000);
            PokerGame game = primedForButtonAdvance(template, 2, 1);

            game.startNewHand();

            assertEquals(0, game.getButtonSeatPosition());
            assertEquals(0, game.getDealerPosition());
        }
    }

    @Nested
    @DisplayName("Missed blinds tracking")
    class MissedBlinds {

        @Test
        @DisplayName("Missed blinds accumulate per seat")
        void accumulatePerSeat() {
            PokerGame game = gameWithChips(1000, 1000);

            assertFalse(game.getMissedBlinds().containsKey(3));

            game.addMissedBlind(3, 10);
            game.addMissedBlind(3, 20);

            assertEquals(30, game.getMissedBlinds().get(3), "amounts accumulate for the same seat");
        }

        @Test
        @DisplayName("Missed blinds survive a new hand when not collected (returning player still owes)")
        void survivesNewHandWhenNotCollected() {
            PokerGame game = gameWithChips(1000, 1000);
            game.startNewHand();
            foldOutHand(game);

            game.addMissedBlind(1, 20);
            game.startNewHand();

            assertEquals(20, game.getMissedBlinds().get(1),
                    "seat 1 posts SB on hand 2 and is skipped by collection — debt remains");
        }
    }

    @Nested
    @DisplayName("Showdown order — last aggressor")
    class LastAggressor {

        @Test
        @DisplayName("Last aggressor resolves to the recorded player")
        void resolvesRecordedPlayer() {
            PokerGame game = gameWithChips(1000, 1000);
            game.startNewHand();
            Player raiser = game.getCurrentPlayer();
            game.executeAction(raiser.getId(), PlayerAction.RAISE, Chips.of(60));

            assertEquals(raiser.getId(), game.getLastAggressorId());
            assertSame(raiser, game.getPlayers().stream()
                    .filter(p -> p.getId().equals(game.getLastAggressorId()))
                    .findFirst()
                    .orElseThrow());
        }

        @Test
        @DisplayName("Last aggressor is cleared on a new hand")
        void clearedOnNewHand() {
            PokerGame game = gameWithChips(1000, 1000);
            game.startNewHand();
            game.executeAction(game.getCurrentPlayer().getId(), PlayerAction.RAISE, Chips.of(60));
            assertTrue(game.getLastAggressorId() != null);

            foldOutHand(game);
            game.startNewHand();

            assertNull(game.getLastAggressorId());
        }
    }
}
