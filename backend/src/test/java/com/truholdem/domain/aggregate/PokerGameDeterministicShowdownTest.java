package com.truholdem.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.domain.value.Chips;
import com.truholdem.model.Card;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.model.Suit;
import com.truholdem.model.Value;

/**
 * Golden, deterministic showdown/side-pot test using the {@link PokerGame#useFixedDeck(List) fixed-deck} seam:
 * the exact card order is pinned, so the winner and the side-pot distribution are reproducible (not just the
 * chip-conservation invariant already covered by {@code PokerGameShowdownTest}).
 *
 * <p>Three unequal stacks all-in pre-flop create a main pot + two side pots; the hole cards are dealt so the
 * shortest stack has the best hand — locking each pot's winner: P2 (AA) takes the main pot, P1 (KK) the first
 * side pot, and P0 (QQ) gets the uncontested second side pot back.
 */
@DisplayName("PokerGame — deterministic side-pot distribution (fixed deck)")
class PokerGameDeterministicShowdownTest {

    private static Card card(Suit suit, Value value) {
        return new Card(suit, value);
    }

    /**
     * Deal order for 3 players: hole cards round-robin (P0,P1,P2 then P0,P1,P2), then burn+flop(3),
     * burn+turn(1), burn+river(1). Board ends up 2♣ 7♦ 9♠ J♥ 4♣ (no pair/straight/flush), so the pocket pairs
     * rank cleanly AA &gt; KK &gt; QQ. Remaining cards fill the rest of the 52 in any order.
     */
    private static List<Card> fixedDeck() {
        List<Card> d = new ArrayList<>();
        // hole: P0=Q, P1=K, P2=A (first card each), then second card each
        d.add(card(Suit.SPADES, Value.QUEEN));
        d.add(card(Suit.SPADES, Value.KING));
        d.add(card(Suit.SPADES, Value.ACE));
        d.add(card(Suit.HEARTS, Value.QUEEN));
        d.add(card(Suit.HEARTS, Value.KING));
        d.add(card(Suit.HEARTS, Value.ACE));
        // burn + flop
        d.add(card(Suit.SPADES, Value.TWO));
        d.add(card(Suit.CLUBS, Value.TWO));
        d.add(card(Suit.DIAMONDS, Value.SEVEN));
        d.add(card(Suit.SPADES, Value.NINE));
        // burn + turn
        d.add(card(Suit.SPADES, Value.THREE));
        d.add(card(Suit.HEARTS, Value.JACK));
        // burn + river
        d.add(card(Suit.SPADES, Value.FIVE));
        d.add(card(Suit.CLUBS, Value.FOUR));
        // fillers: every card not already used
        Set<String> used = new HashSet<>();
        d.forEach(c -> used.add(c.toString()));
        for (Suit s : Suit.values()) {
            for (Value v : Value.values()) {
                Card c = card(s, v);
                if (used.add(c.toString())) {
                    d.add(c);
                }
            }
        }
        return d;
    }

    @Test
    @DisplayName("main + two side pots are awarded to the right players for the pinned board")
    void sidePotDistributionIsDeterministic() {
        PokerGame game = PokerGame.create(List.of(
                new PlayerInfo("P0", 1000, false),
                new PlayerInfo("P1", 500, false),
                new PlayerInfo("P2", 200, false)),
                Chips.of(10), Chips.of(20));
        game.useFixedDeck(fixedDeck());
        game.startNewHand();

        int guard = 0;
        while (game.getPhase() != GamePhase.FINISHED && guard++ < 30) {
            Player current = game.getCurrentPlayer();
            if (current == null) {
                break;
            }
            game.executeAction(current.getId(), PlayerAction.ALL_IN, null);
        }

        assertEquals(GamePhase.FINISHED, game.getPhase(), "all-in pre-flop must run to showdown");

        Map<String, Integer> chips = new HashMap<>();
        game.getPlayers().forEach(p -> chips.put(p.getName(), p.getChips()));

        // 1700 in play: main pot 600 (all three), side pot 1 600 (P0,P1), side pot 2 500 (P0 only).
        assertEquals(600, chips.get("P2"), "AA wins the main pot");
        assertEquals(600, chips.get("P1"), "KK wins side pot 1");
        assertEquals(500, chips.get("P0"), "QQ gets the uncontested side pot 2 back");
        assertEquals(1700, chips.values().stream().mapToInt(Integer::intValue).sum(), "chips conserved");
    }
}
