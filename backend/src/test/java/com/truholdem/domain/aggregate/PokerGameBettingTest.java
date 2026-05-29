package com.truholdem.domain.aggregate;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PokerGame Aggregate — betting, dead button & missed blinds")
class PokerGameBettingTest {

    private static final Chips SMALL_BLIND = Chips.of(10);
    private static final Chips BIG_BLIND = Chips.of(20);

    private static PokerGame headsUp() {
        return PokerGame.create(List.of(
                new PlayerInfo("Alice", 1000, false),
                new PlayerInfo("Bob", 1000, false)), SMALL_BLIND, BIG_BLIND);
    }

    private static PokerGame threeHanded() {
        return PokerGame.create(List.of(
                new PlayerInfo("Alice", 1000, false),
                new PlayerInfo("Bob", 1000, false),
                new PlayerInfo("Charlie", 1000, false)), SMALL_BLIND, BIG_BLIND);
    }

    @Nested
    @DisplayName("Raise semantics")
    class RaiseSemantics {

        @Test
        @DisplayName("RAISE amount is the target TOTAL bet, not an increment")
        void raiseIsTotalBet() {
            PokerGame game = headsUp();
            game.startNewHand();

            Player raiser = game.getCurrentPlayer();
            game.executeAction(raiser.getId(), PlayerAction.RAISE, Chips.of(60));

            assertEquals(60, game.getCurrentBet().amount(), "current bet should equal the target total");
            assertEquals(60, game.findPlayerById(raiser.getId()).getBetAmount(),
                    "raiser's committed bet should equal the target total");
            // raise increment above the previous bet (20) is 40
            assertEquals(40, game.getMinRaise().amount());
            assertEquals(40, game.getLastRaiseAmount());
        }
    }

    @Nested
    @DisplayName("Dead button")
    class DeadButton {

        @Test
        @DisplayName("button seat advances clockwise across hands")
        void buttonAdvancesAcrossHands() {
            PokerGame game = threeHanded();
            game.startNewHand();
            assertEquals(0, game.getButtonSeatPosition());

            // End the hand by folding until one player remains
            int guard = 0;
            while (game.getPhase() != GamePhase.FINISHED && guard++ < 50) {
                Player current = game.getCurrentPlayer();
                if (current == null) {
                    break;
                }
                game.executeAction(current.getId(), PlayerAction.FOLD, null);
            }

            game.startNewHand();

            assertEquals(1, game.getButtonSeatPosition());
            assertEquals(1, game.getDealerPosition());
            assertFalse(game.isDeadButton());
        }
    }

    @Nested
    @DisplayName("Missed blinds")
    class MissedBlinds {

        @Test
        @DisplayName("a player who owes a missed blind posts it as dead money at hand start")
        void missedBlindCollectedToPot() {
            PokerGame game = threeHanded();
            // Seat 0 is the button (posts no blind 3-handed) -> charge it a missed blind
            game.addMissedBlind(0, 20);

            game.startNewHand();

            Player buttonPlayer = game.getPlayers().get(0);
            assertEquals(980, buttonPlayer.getChips(), "missed blind should be deducted as dead money");
            // SB(10) + BB(20) + missed dead money(20) = 50
            assertEquals(Chips.of(50), game.getPotSize());
            assertTrue(game.getMissedBlinds().isEmpty(), "missed blind should be cleared after posting");
        }
    }
}
