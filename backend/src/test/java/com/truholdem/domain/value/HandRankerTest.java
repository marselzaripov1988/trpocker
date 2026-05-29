package com.truholdem.domain.value;

import com.truholdem.model.Card;
import com.truholdem.model.HandRanking;
import com.truholdem.model.HandType;
import com.truholdem.model.Suit;
import com.truholdem.model.Value;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HandRanker — pure domain hand evaluation")
class HandRankerTest {

    private static Card card(Value value, Suit suit) {
        return new Card(suit, value);
    }

    private static HandRanking rank(List<Card> hole, List<Card> board) {
        return HandRanker.evaluate(hole, board);
    }

    @Nested
    @DisplayName("Category detection")
    class Categories {

        @Test
        @DisplayName("detects a royal flush")
        void royalFlush() {
            HandRanking r = rank(
                    List.of(card(Value.ACE, Suit.HEARTS), card(Value.KING, Suit.HEARTS)),
                    List.of(card(Value.QUEEN, Suit.HEARTS), card(Value.JACK, Suit.HEARTS),
                            card(Value.TEN, Suit.HEARTS), card(Value.TWO, Suit.CLUBS),
                            card(Value.THREE, Suit.DIAMONDS)));
            assertEquals(HandType.ROYAL_FLUSH, r.getHandType());
        }

        @Test
        @DisplayName("detects a wheel straight (A-2-3-4-5)")
        void wheelStraight() {
            HandRanking r = rank(
                    List.of(card(Value.ACE, Suit.HEARTS), card(Value.TWO, Suit.CLUBS)),
                    List.of(card(Value.THREE, Suit.DIAMONDS), card(Value.FOUR, Suit.SPADES),
                            card(Value.FIVE, Suit.HEARTS), card(Value.KING, Suit.CLUBS),
                            card(Value.QUEEN, Suit.DIAMONDS)));
            assertEquals(HandType.STRAIGHT, r.getHandType());
            assertEquals(Value.FIVE, r.getRankValues().get(0));
        }

        @Test
        @DisplayName("detects a full house")
        void fullHouse() {
            HandRanking r = rank(
                    List.of(card(Value.KING, Suit.HEARTS), card(Value.KING, Suit.CLUBS)),
                    List.of(card(Value.KING, Suit.DIAMONDS), card(Value.QUEEN, Suit.SPADES),
                            card(Value.QUEEN, Suit.HEARTS), card(Value.TWO, Suit.CLUBS),
                            card(Value.THREE, Suit.DIAMONDS)));
            assertEquals(HandType.FULL_HOUSE, r.getHandType());
        }

        @Test
        @DisplayName("returns null when fewer than 7 cards are available")
        void notEnoughCards() {
            assertNull(rank(
                    List.of(card(Value.ACE, Suit.HEARTS), card(Value.KING, Suit.HEARTS)),
                    List.of(card(Value.QUEEN, Suit.HEARTS))));
        }
    }

    @Nested
    @DisplayName("Comparisons")
    class Comparisons {

        @Test
        @DisplayName("flush beats a straight")
        void flushBeatsStraight() {
            List<Card> board = List.of(
                    card(Value.NINE, Suit.HEARTS), card(Value.SIX, Suit.HEARTS),
                    card(Value.TWO, Suit.HEARTS), card(Value.SEVEN, Suit.CLUBS),
                    card(Value.EIGHT, Suit.DIAMONDS));
            HandRanking flush = rank(
                    List.of(card(Value.ACE, Suit.HEARTS), card(Value.FOUR, Suit.HEARTS)), board);
            HandRanking straight = rank(
                    List.of(card(Value.TEN, Suit.SPADES), card(Value.FIVE, Suit.CLUBS)), board);

            assertEquals(HandType.FLUSH, flush.getHandType());
            assertEquals(HandType.STRAIGHT, straight.getHandType());
            assertTrue(flush.compareTo(straight) > 0);
        }

        @Test
        @DisplayName("higher kicker wins with an identical pair")
        void kickerDecidesPair() {
            List<Card> board = List.of(
                    card(Value.TEN, Suit.HEARTS), card(Value.TEN, Suit.CLUBS),
                    card(Value.TWO, Suit.DIAMONDS), card(Value.FIVE, Suit.SPADES),
                    card(Value.SEVEN, Suit.CLUBS));
            HandRanking aceKicker = rank(
                    List.of(card(Value.ACE, Suit.HEARTS), card(Value.THREE, Suit.CLUBS)), board);
            HandRanking kingKicker = rank(
                    List.of(card(Value.KING, Suit.HEARTS), card(Value.FOUR, Suit.CLUBS)), board);

            assertEquals(HandType.ONE_PAIR, aceKicker.getHandType());
            assertEquals(HandType.ONE_PAIR, kingKicker.getHandType());
            assertTrue(aceKicker.compareTo(kingKicker) > 0);
        }

        @Test
        @DisplayName("identical best hands tie")
        void identicalHandsTie() {
            List<Card> board = List.of(
                    card(Value.ACE, Suit.HEARTS), card(Value.KING, Suit.CLUBS),
                    card(Value.QUEEN, Suit.DIAMONDS), card(Value.JACK, Suit.SPADES),
                    card(Value.TEN, Suit.CLUBS));
            HandRanking a = rank(
                    List.of(card(Value.TWO, Suit.HEARTS), card(Value.THREE, Suit.CLUBS)), board);
            HandRanking b = rank(
                    List.of(card(Value.FOUR, Suit.DIAMONDS), card(Value.FIVE, Suit.SPADES)), board);

            // Both play the board's broadway straight
            assertEquals(HandType.STRAIGHT, a.getHandType());
            assertEquals(HandType.STRAIGHT, b.getHandType());
            assertEquals(0, a.compareTo(b));
        }
    }
}
