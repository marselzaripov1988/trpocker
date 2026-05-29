package com.truholdem.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.domain.value.Chips;
import com.truholdem.model.Card;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
import com.truholdem.model.HandLifecycleState;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.model.Suit;
import com.truholdem.model.Value;

@DisplayName("PokerGameMapper — Game ⇄ aggregate bridge")
class PokerGameMapperTest {

    private static final Chips SMALL_BLIND = Chips.of(10);
    private static final Chips BIG_BLIND = Chips.of(20);

    private final PokerGameMapper mapper = new PokerGameMapper();

    @Nested
    @DisplayName("Round-trip")
    class RoundTrip {

        @Test
        @DisplayName("captureState → reconstitute preserves hand state")
        void aggregateRoundTrip() {
            PokerGame original = PokerGame.create(List.of(
                    new PlayerInfo("Alice", 1000, false),
                    new PlayerInfo("Bob", 1000, false)), SMALL_BLIND, BIG_BLIND);
            original.startNewHand();

            PokerGame restored = PokerGame.reconstitute(original.captureState());

            assertEquals(original.getPhase(), restored.getPhase());
            assertEquals(original.getHandNumber(), restored.getHandNumber());
            assertEquals(original.getMainPotAmount(), restored.getMainPotAmount());
            assertEquals(original.getCurrentBet(), restored.getCurrentBet());
            assertEquals(original.getDealerPosition(), restored.getDealerPosition());
            assertEquals(original.getButtonSeatPosition(), restored.getButtonSeatPosition());
            assertEquals(original.isDeadButton(), restored.isDeadButton());
            assertEquals(original.getCurrentPlayerIndex(), restored.getCurrentPlayerIndex());
        }

        @Test
        @DisplayName("fromGame → applyToGame preserves entity fields and lifecycle")
        void entityRoundTrip() {
            Game game = legacyStyleGame();
            HandLifecycleState lifecycle = HandLifecycleState.RESULT_DELAY;
            game.setHandLifecycleState(lifecycle);

            PokerGame aggregate = mapper.fromGame(game);
            aggregate.executeAction(
                    aggregate.getCurrentPlayer().getId(),
                    PlayerAction.CALL,
                    null);

            mapper.applyToGame(aggregate, game);

            assertEquals(aggregate.getPhase(), game.getPhase());
            assertEquals(lifecycle, game.getHandLifecycleState(),
                    "hand lifecycle must remain service-owned");
            assertEquals(aggregate.getMainPotAmount(), game.getCurrentPot());
            assertEquals(aggregate.getCurrentBet().amount(), game.getCurrentBet());
            assertEquals(aggregate.getMinRaise().amount(), game.getMinRaiseAmount());
            assertEquals(aggregate.getLastRaiseAmount(), game.getLastRaiseAmount());
            assertEquals(aggregate.getButtonSeatPosition(), game.getButtonSeatPosition());
            assertFalse(game.isDeadButton());
        }

        @Test
        @DisplayName("fromGame shares player references so aggregate actions mutate the entity")
        void sharedPlayerReferences() {
            Game game = legacyStyleGame();
            Player hero = game.getPlayers().get(0);
            int chipsBefore = hero.getChips();

            PokerGame aggregate = mapper.fromGame(game);
            aggregate.executeAction(hero.getId(), PlayerAction.FOLD, null);

            assertSame(hero, aggregate.getPlayers().get(0));
            assertEquals(chipsBefore, hero.getChips());
        }
    }

    @Nested
    @DisplayName("applyToGame")
    class ApplyToGame {

        private Game game;
        private PokerGame aggregate;

        @BeforeEach
        void setUp() {
            aggregate = PokerGame.create(List.of(
                    new PlayerInfo("Alice", 1000, false),
                    new PlayerInfo("Bob", 1000, false)), SMALL_BLIND, BIG_BLIND);
            aggregate.startNewHand();

            game = new Game();
            game.setId(aggregate.getId());
            game.setHandLifecycleState(HandLifecycleState.IN_PROGRESS);
            for (Player player : aggregate.getPlayers()) {
                game.addPlayer(player);
            }
        }

        @Test
        @DisplayName("copies deck and community cards onto the entity")
        void copiesCardLists() {
            mapper.applyToGame(aggregate, game);

            assertEquals(aggregate.getDeck().size(), game.getDeck().size());
            assertEquals(aggregate.getCommunityCards().size(), game.getCommunityCards().size());
        }

        @Test
        @DisplayName("clears side pots (aggregate keeps pot in main during the hand)")
        void clearsSidePots() {
            game.addSidePot(new com.truholdem.model.SidePot(50, List.of(UUID.randomUUID()), 25));

            mapper.applyToGame(aggregate, game);

            assertEquals(0, game.getSidePots().size());
        }
    }

    private static Game legacyStyleGame() {
        Game game = new Game();
        game.setId(UUID.randomUUID());
        game.setSmallBlind(SMALL_BLIND.amount());
        game.setBigBlind(BIG_BLIND.amount());
        game.setMinRaiseAmount(BIG_BLIND.amount());
        game.setLastRaiseAmount(BIG_BLIND.amount());
        game.setPhase(GamePhase.PRE_FLOP);
        game.setHandNumber(1);
        game.setDealerPosition(0);
        game.setButtonSeatPosition(0);
        game.setCurrentPlayerIndex(0);

        Player alice = new Player("Alice", 990, false);
        alice.setSeatPosition(0);
        alice.setBetAmount(10);
        alice.setTotalBetInRound(10);
        alice.getHand().add(new Card(Suit.SPADES, Value.ACE));
        alice.getHand().add(new Card(Suit.HEARTS, Value.KING));

        Player bob = new Player("Bob", 980, false);
        bob.setSeatPosition(1);
        bob.setBetAmount(20);
        bob.setTotalBetInRound(20);
        bob.setHasActed(true);

        game.addPlayer(alice);
        game.addPlayer(bob);
        game.setCurrentPot(30);
        game.setCurrentBet(20);

        for (int i = 0; i < 40; i++) {
            game.getDeck().add(new Card(Suit.CLUBS, Value.TWO));
        }
        return game;
    }
}
