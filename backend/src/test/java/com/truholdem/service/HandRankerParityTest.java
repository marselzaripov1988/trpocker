package com.truholdem.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.domain.value.HandRanker;
import com.truholdem.model.Card;
import com.truholdem.model.HandRanking;
import com.truholdem.model.Suit;
import com.truholdem.model.Value;

/**
 * Cross-engine showdown-ranking parity guard for the engine migration. Both engines determine the showdown
 * winner through the <b>same</b> ranker: the legacy {@code PokerGameService} via {@link HandEvaluator} (which
 * delegates to {@link HandRanker}) and the aggregate {@code PokerGame} via {@link HandRanker} directly. So the
 * showdown <i>winner</i> is structurally identical across engines — this test pins that single source of truth
 * so a future fork of {@code HandEvaluator} (re-introducing a second ranker) is caught immediately. Only the
 * pot/side-pot <i>distribution</i> is per-engine code (the aggregate's is pinned by
 * {@code PokerGameDeterministicShowdownTest}; the legacy's is the long-standing default).
 */
@DisplayName("Engine parity: legacy HandEvaluator and aggregate HandRanker rank hands identically")
class HandRankerParityTest {

    private final HandEvaluator legacy = new HandEvaluator();

    private static Card c(Suit suit, Value value) {
        return new Card(suit, value);
    }

    private void assertSameRanking(List<Card> hole, List<Card> board) {
        HandRanking viaLegacy = legacy.evaluate(hole, board);
        HandRanking viaAggregate = HandRanker.evaluate(hole, board);
        assertThat(viaAggregate)
                .as("legacy HandEvaluator must rank identically to the aggregate HandRanker")
                .isEqualTo(viaLegacy);
    }

    @Test
    @DisplayName("every hand category ranks identically through both engines' rankers")
    void rankersAgreeAcrossCategories() {
        // high card
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.ACE), c(Suit.DIAMONDS, Value.KING)),
                List.of(c(Suit.CLUBS, Value.QUEEN), c(Suit.HEARTS, Value.JACK), c(Suit.SPADES, Value.NINE),
                        c(Suit.DIAMONDS, Value.SEVEN), c(Suit.CLUBS, Value.TWO)));
        // one pair
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.ACE), c(Suit.DIAMONDS, Value.ACE)),
                List.of(c(Suit.CLUBS, Value.KING), c(Suit.HEARTS, Value.QUEEN), c(Suit.SPADES, Value.NINE),
                        c(Suit.DIAMONDS, Value.SEVEN), c(Suit.CLUBS, Value.TWO)));
        // two pair
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.ACE), c(Suit.DIAMONDS, Value.ACE)),
                List.of(c(Suit.CLUBS, Value.KING), c(Suit.HEARTS, Value.KING), c(Suit.SPADES, Value.NINE),
                        c(Suit.DIAMONDS, Value.SEVEN), c(Suit.CLUBS, Value.TWO)));
        // trips
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.ACE), c(Suit.DIAMONDS, Value.ACE)),
                List.of(c(Suit.CLUBS, Value.ACE), c(Suit.HEARTS, Value.KING), c(Suit.SPADES, Value.NINE),
                        c(Suit.DIAMONDS, Value.SEVEN), c(Suit.CLUBS, Value.TWO)));
        // straight
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.FIVE), c(Suit.DIAMONDS, Value.SIX)),
                List.of(c(Suit.CLUBS, Value.SEVEN), c(Suit.HEARTS, Value.EIGHT), c(Suit.SPADES, Value.NINE),
                        c(Suit.DIAMONDS, Value.TWO), c(Suit.CLUBS, Value.THREE)));
        // flush
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.ACE), c(Suit.SPADES, Value.KING)),
                List.of(c(Suit.SPADES, Value.QUEEN), c(Suit.SPADES, Value.JACK), c(Suit.SPADES, Value.NINE),
                        c(Suit.DIAMONDS, Value.SEVEN), c(Suit.CLUBS, Value.TWO)));
        // full house
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.ACE), c(Suit.DIAMONDS, Value.ACE)),
                List.of(c(Suit.CLUBS, Value.ACE), c(Suit.HEARTS, Value.KING), c(Suit.SPADES, Value.KING),
                        c(Suit.DIAMONDS, Value.SEVEN), c(Suit.CLUBS, Value.TWO)));
        // four of a kind
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.ACE), c(Suit.DIAMONDS, Value.ACE)),
                List.of(c(Suit.CLUBS, Value.ACE), c(Suit.HEARTS, Value.ACE), c(Suit.SPADES, Value.KING),
                        c(Suit.DIAMONDS, Value.SEVEN), c(Suit.CLUBS, Value.TWO)));
        // straight flush
        assertSameRanking(
                List.of(c(Suit.SPADES, Value.FIVE), c(Suit.SPADES, Value.SIX)),
                List.of(c(Suit.SPADES, Value.SEVEN), c(Suit.SPADES, Value.EIGHT), c(Suit.SPADES, Value.NINE),
                        c(Suit.DIAMONDS, Value.TWO), c(Suit.CLUBS, Value.THREE)));
    }

    @Test
    @DisplayName("kicker tie-breakers rank identically through both rankers")
    void rankersAgreeOnKickers() {
        // same pair of aces, different kickers — both rankers must order them the same way
        List<Card> board = List.of(c(Suit.CLUBS, Value.ACE), c(Suit.HEARTS, Value.QUEEN), c(Suit.SPADES, Value.NINE),
                c(Suit.DIAMONDS, Value.SEVEN), c(Suit.CLUBS, Value.TWO));
        HandRanking aggKing = HandRanker.evaluate(
                List.of(c(Suit.SPADES, Value.KING), c(Suit.DIAMONDS, Value.THREE)), board);
        HandRanking aggJack = HandRanker.evaluate(
                List.of(c(Suit.SPADES, Value.JACK), c(Suit.DIAMONDS, Value.THREE)), board);
        HandRanking legKing = legacy.evaluate(
                List.of(c(Suit.SPADES, Value.KING), c(Suit.DIAMONDS, Value.THREE)), board);
        HandRanking legJack = legacy.evaluate(
                List.of(c(Suit.SPADES, Value.JACK), c(Suit.DIAMONDS, Value.THREE)), board);

        assertThat(aggKing).isEqualTo(legKing);
        assertThat(aggJack).isEqualTo(legJack);
        // king kicker beats jack kicker in both rankers (same sign)
        assertThat(Integer.signum(aggKing.compareTo(aggJack)))
                .as("kicker ordering matches across rankers")
                .isEqualTo(Integer.signum(legKing.compareTo(legJack)))
                .isPositive();
    }
}
