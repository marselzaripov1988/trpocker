package com.truholdem.service;

import com.truholdem.domain.value.HandRanker;
import com.truholdem.model.Card;
import com.truholdem.model.HandRanking;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring-facing hand evaluator. Delegates all ranking logic to the pure, dependency-free
 * {@link HandRanker} in the domain layer so there is a single source of truth shared by the
 * legacy {@code PokerGameService} path and the domain aggregate.
 */
@Component
public class HandEvaluator {

    public HandRanking evaluate(List<Card> playerHand, List<Card> communityCards) {
        return HandRanker.evaluate(playerHand, communityCards);
    }
}
