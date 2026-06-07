package com.truholdem.mapper;

import com.truholdem.domain.aggregate.PersistedGameState;
import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.model.Card;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
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

    /** A hold'em match needs at least two players holding chips; below that the match is over. */
    private static final int MIN_PLAYERS_FOR_MATCH = 2;

    public PokerGame fromGame(Game game) {
        Objects.requireNonNull(game, "Game cannot be null");
        return PokerGame.reconstitute(toPersistedState(game));
    }

 
    public void applyToGame(PokerGame aggregate, Game game) {
        Objects.requireNonNull(aggregate, "Aggregate cannot be null");
        Objects.requireNonNull(game, "Game cannot be null");

        HandLifecycleState lifecycle = game.getHandLifecycleState();

        applyPhaseAndPot(aggregate, game);
        game.setCurrentBet(aggregate.getCurrentBet().amount());
        game.setSmallBlind(aggregate.getSmallBlind().amount());
        game.setBigBlind(aggregate.getBigBlind().amount());
        game.setDealerPosition(aggregate.getDealerPosition());
        game.setCurrentPlayerIndex(aggregate.getCurrentPlayerIndex());
        game.setHandNumber(aggregate.getHandNumber());
        // game.finished means "the current hand is done" on the wire (legacy/test semantics); it is owned by
        // applyPhaseAndPot below (true iff phase == FINISHED). The aggregate's own isFinished() is the *match-over*
        // concept and must NOT be written here — doing so made a finished hand reconstitute as a finished match and
        // broke multi-hand flow. Match-over is re-derived from chip counts on the way back in toPersistedState.
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
                isMatchOver(game),
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

    /**
     * Maps aggregate hand-end semantics to the legacy {@link Game} wire format
     * ({@code isFinished=true}, {@code phase=SHOWDOWN}, pot cleared).
     */
    private static void applyPhaseAndPot(PokerGame aggregate, Game game) {
        if (aggregate.getPhase() == GamePhase.FINISHED) {
            game.setFinished(true);
            game.setPhase(GamePhase.SHOWDOWN);
            game.setCurrentPot(0);
        } else {
            game.setFinished(false);
            game.setPhase(aggregate.getPhase());
            game.setCurrentPot(aggregate.getMainPotAmount());
        }
    }

    /**
     * Re-derives the aggregate's <i>match-over</i> flag from a {@link Game} whose {@code finished} column carries
     * the wire <i>hand-done</i> meaning. A match is over only once the current hand has finished <b>and</b> fewer
     * than {@link #MIN_PLAYERS_FOR_MATCH} players still hold chips. Gating on hand-done is essential: mid-hand a
     * player can sit at zero chips (all-in) without the match being over, so a pure chip-count check would falsely
     * end heads-up all-in hands on reload. This mirrors {@code PokerGame.completeHand} exactly (phase == FINISHED,
     * then players-with-chips &lt; MIN_PLAYERS).
     */
    private static boolean isMatchOver(Game game) {
        if (!game.isFinished()) {
            return false;
        }
        long withChips = game.getPlayers().stream().filter(p -> p.getChips() > 0).count();
        return withChips < MIN_PLAYERS_FOR_MATCH;
    }
}
