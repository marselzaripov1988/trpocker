package com.truholdem.mapper;

import com.truholdem.domain.aggregate.PersistedGameState;
import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.model.Card;
import com.truholdem.model.Game;
import com.truholdem.model.HandLifecycleState;
import com.truholdem.model.Player;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bidirectional bridge between the rich {@link PokerGame} aggregate and the JPA
 * {@link Game} entity the live service persists and broadcasts.
 *
 * <p>{@link HandLifecycleState} is owned by {@code PokerGameService} (result delay,
 * next-hand scheduling) and is never read or written by this mapper.
 */
@Component
public class PokerGameMapper {

    public PokerGame fromGame(Game game) {
        Objects.requireNonNull(game, "Game cannot be null");
        return PokerGame.reconstitute(toPersistedState(game));
    }

 
    public void applyToGame(PokerGame aggregate, Game game) {
        Objects.requireNonNull(aggregate, "Aggregate cannot be null");
        Objects.requireNonNull(game, "Game cannot be null");

        HandLifecycleState lifecycle = game.getHandLifecycleState();

        game.setPhase(aggregate.getPhase());
        game.setCurrentPot(aggregate.getMainPotAmount());
        game.setCurrentBet(aggregate.getCurrentBet().amount());
        game.setSmallBlind(aggregate.getSmallBlind().amount());
        game.setBigBlind(aggregate.getBigBlind().amount());
        game.setDealerPosition(aggregate.getDealerPosition());
        game.setCurrentPlayerIndex(aggregate.getCurrentPlayerIndex());
        game.setHandNumber(aggregate.getHandNumber());
        game.setFinished(aggregate.isFinished());
        game.setWinnerName(aggregate.getWinnerName());
        game.setWinningHandDescription(aggregate.getWinningHandDescription());
        game.setLastRaiseAmount(aggregate.getLastRaiseAmount());
        game.setMinRaiseAmount(aggregate.getMinRaise().amount());
        game.setLastAggressorId(aggregate.getLastAggressorId());
        game.setButtonSeatPosition(aggregate.getButtonSeatPosition());
        game.setDeadButton(aggregate.isDeadButton());

        replaceCards(game.getCommunityCards(), aggregate.getCommunityCards());
        replaceCards(game.getDeck(), aggregate.getDeck());

        game.getWinnerIds().clear();
        game.getWinnerIds().addAll(aggregate.getWinnerIds());

        game.getMissedBlinds().clear();
        game.getMissedBlinds().putAll(aggregate.getMissedBlinds());

        game.getSidePots().clear();

        syncPlayers(aggregate, game);

        game.setHandLifecycleState(lifecycle);
    }

    public PersistedGameState toPersistedState(Game game) {
        Objects.requireNonNull(game, "Game cannot be null");

        return new PersistedGameState(
                game.getId(),
                game.getVersion(),
                game.getCreatedAt(),
                game.getUpdatedAt(),
                game.getSmallBlind(),
                game.getBigBlind(),
                game.getPhase(),
                game.getDealerPosition(),
                game.getCurrentPlayerIndex(),
                game.getHandNumber(),
                game.isFinished(),
                game.getCurrentBet(),
                game.getMinRaiseAmount(),
                game.getLastRaiseAmount(),
                0,
                game.getLastAggressorId(),
                game.getButtonSeatPosition(),
                game.isDeadButton(),
                new HashMap<>(game.getMissedBlinds()),
                game.getCurrentPot(),
                new ArrayList<>(game.getPlayers()),
                new ArrayList<>(game.getCommunityCards()),
                new ArrayList<>(game.getDeck()),
                game.getWinnerName(),
                game.getWinningHandDescription(),
                new ArrayList<>(game.getWinnerIds()));
    }

    private void syncPlayers(PokerGame aggregate, Game game) {
        List<Player> aggregatePlayers = aggregate.getPlayers();
        List<Player> gamePlayers = game.getPlayers();

        if (gamePlayers == aggregatePlayers) {
            for (Player player : gamePlayers) {
                player.setGame(game);
            }
            return;
        }

        gamePlayers.clear();
        for (Player player : aggregatePlayers) {
            game.addPlayer(player);
        }
    }

    private static void replaceCards(List<Card> target, List<Card> source) {
        target.clear();
        target.addAll(source);
    }
}
