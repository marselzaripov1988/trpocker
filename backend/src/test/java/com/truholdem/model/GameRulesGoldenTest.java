package com.truholdem.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Golden regression tests for official-poker-rule bookkeeping on {@link Game}:
 * dead button advancement, missed-blind tracking, and last-aggressor (showdown order).
 *
 * <p>These rules are currently exercised only indirectly through the large
 * {@code PokerGameService} suite. They are pinned here as fast, dependency-free unit
 * tests so the behaviour is locked down before the planned engine migration (Phase 0).
 */
@DisplayName("Game rules — golden safety net")
class GameRulesGoldenTest {

    private static Player seat(int seatPosition, int chips) {
        Player player = new Player("Player" + seatPosition, chips, false);
        player.setId(UUID.randomUUID());
        player.setSeatPosition(seatPosition);
        return player;
    }

    private static Game gameWith(Player... players) {
        Game game = new Game();
        game.setId(UUID.randomUUID());
        for (Player player : players) {
            game.addPlayer(player);
        }
        return game;
    }

    @Nested
    @DisplayName("Dead button advancement")
    class DeadButton {

        @Test
        @DisplayName("Button moves to the next clockwise seat when it is occupied")
        void advancesToNextOccupiedSeat() {
            Game game = gameWith(seat(0, 1000), seat(1, 1000), seat(2, 1000));
            game.setButtonSeatPosition(0);

            game.resetForNewHand();

            assertEquals(1, game.getButtonSeatPosition());
            assertFalse(game.isDeadButton(), "button is live when a player occupies the seat");
            assertEquals(1, game.getDealerPosition());
        }

        @Test
        @DisplayName("Button is dead when the next seat has no eligible player")
        void deadButtonWhenNextSeatEmpty() {
            // Seat 1 player is busted (0 chips) -> the button seat itself has no eligible player.
            Game game = gameWith(seat(0, 1000), seat(1, 0), seat(2, 1000));
            game.setButtonSeatPosition(0);

            game.resetForNewHand();

            assertTrue(game.isDeadButton(), "no eligible player at the next seat => dead button");
            assertEquals(1, game.getButtonSeatPosition(), "button seat still advances clockwise");
            // Dealer duties move to the next active player clockwise (seat 2, index 2).
            assertEquals(2, game.getDealerPosition());
        }

        @Test
        @DisplayName("Button seat wraps around the table")
        void wrapsAroundTable() {
            Game game = gameWith(seat(0, 1000), seat(1, 1000), seat(2, 1000));
            game.setButtonSeatPosition(2);

            game.resetForNewHand();

            assertEquals(0, game.getButtonSeatPosition());
            assertEquals(0, game.getDealerPosition());
        }
    }

    @Nested
    @DisplayName("Missed blinds tracking")
    class MissedBlinds {

        @Test
        @DisplayName("Missed blinds accumulate per seat and can be cleared")
        void accumulateAndClear() {
            Game game = gameWith(seat(0, 1000));

            assertEquals(0, game.getMissedBlindAmount(3), "no missed blind by default");

            game.addMissedBlind(3, 10);
            game.addMissedBlind(3, 20);
            assertEquals(30, game.getMissedBlindAmount(3), "amounts accumulate for the same seat");

            game.clearMissedBlind(3);
            assertEquals(0, game.getMissedBlindAmount(3), "cleared seat owes nothing");
        }

        @Test
        @DisplayName("Missed blinds survive a new-hand reset (returning players still owe)")
        void survivesNewHandReset() {
            Game game = gameWith(seat(0, 1000), seat(1, 1000));
            game.addMissedBlind(1, 20);

            game.resetForNewHand();

            assertEquals(20, game.getMissedBlindAmount(1));
        }
    }

    @Nested
    @DisplayName("Showdown order — last aggressor")
    class LastAggressor {

        @Test
        @DisplayName("Last aggressor resolves to the recorded player")
        void resolvesRecordedPlayer() {
            Player p0 = seat(0, 1000);
            Player p1 = seat(1, 1000);
            Game game = gameWith(p0, p1);

            game.setLastAggressorId(p1.getId());

            assertSame(p1, game.getLastAggressor());
        }

        @Test
        @DisplayName("Last aggressor is cleared on a new hand")
        void clearedOnNewHand() {
            Player p0 = seat(0, 1000);
            Player p1 = seat(1, 1000);
            Game game = gameWith(p0, p1);
            game.setLastAggressorId(p1.getId());

            game.resetForNewHand();

            assertNull(game.getLastAggressorId());
            assertNull(game.getLastAggressor());
        }
    }
}
