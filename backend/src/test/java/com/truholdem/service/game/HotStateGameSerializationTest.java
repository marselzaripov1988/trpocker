package com.truholdem.service.game;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.GameStateRedisConfig;
import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.domain.value.Chips;
import com.truholdem.mapper.PokerGameMapper;
import com.truholdem.model.Card;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
import com.truholdem.model.HandLifecycleState;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.model.Suit;
import com.truholdem.model.Value;

/**
 * Characterizes the JSON round-trip that {@link RedisGameStateStore} performs for hot-state. The store
 * serializes the whole {@link Game} entity to a Redis string and reads it back on a cache hit; if the
 * authoritative state loses the deck or the players' hole cards in that round-trip, a cache hit hands the engine
 * an unplayable game. Uses the same builder Spring Boot configures the application {@code ObjectMapper} with.
 */
@DisplayName("Hot-state Game JSON round-trip")
class HotStateGameSerializationTest {

    private final ObjectMapper defaultMapper = Jackson2ObjectMapperBuilder.json().build();
    private final ObjectMapper hotStateMapper = Jackson2ObjectMapperBuilder.json().build()
            .addMixIn(Game.class, GameStateRedisConfig.GameHotStateMixin.class);

    private Game sampleGame() {
        Game game = new Game();
        game.setHandNumber(1);

        Player alice = new Player("Alice", 990, false);
        alice.getHand().add(new Card(Suit.SPADES, Value.ACE));
        alice.getHand().add(new Card(Suit.HEARTS, Value.KING));
        game.addPlayer(alice);

        game.getDeck().add(new Card(Suit.CLUBS, Value.TWO));
        game.getDeck().add(new Card(Suit.DIAMONDS, Value.SEVEN));
        return game;
    }

    @Test
    @DisplayName("the REST/default mapper drops the deck (why hot-state needs its own mapper)")
    void defaultMapperLosesDeck() throws Exception {
        Game game = sampleGame();
        Game back = defaultMapper.readValue(defaultMapper.writeValueAsString(game), Game.class);

        // @JsonIgnore on Game.deck hides undealt cards from clients — and, with the default mapper, from Redis too.
        // Hole cards (Player.hand) are NOT @JsonIgnore, so they do survive; only the deck is lost.
        assertThat(back.getDeck()).as("default mapper drops the @JsonIgnore deck").isEmpty();
        assertThat(back.getPlayers().get(0).getHand())
                .as("hole cards are not @JsonIgnore and survive").hasSize(2);
    }

    @Test
    @DisplayName("the hot-state mapper preserves the full state including the deck and version")
    void hotStateMapperPreservesDeckAndVersion() throws Exception {
        Game game = sampleGame();
        setVersion(game, 7L);

        Game back = hotStateMapper.readValue(hotStateMapper.writeValueAsString(game), Game.class);

        // The dedicated hot-state mapper re-exposes the deck so a Redis cache hit returns a fully playable game
        // (the next street can be dealt). Hole cards and community state continue to round-trip too.
        assertThat(back.getDeck()).as("hot-state mapper keeps the deck")
                .containsExactly(new Card(Suit.CLUBS, Value.TWO), new Card(Suit.DIAMONDS, Value.SEVEN));
        assertThat(back.getPlayers().get(0).getHand()).hasSize(2);
        // The optimistic-lock token must survive too, or the async DB writer treats the reload as a transient
        // insert and logs stale-object errors.
        assertThat(back.getVersion()).as("hot-state mapper keeps the @Version token").isEqualTo(7L);
    }

    @Test
    @DisplayName("the default mapper drops the version (so it must not be used for hot-state)")
    void defaultMapperLosesVersion() throws Exception {
        Game game = sampleGame();
        setVersion(game, 7L);

        Game back = defaultMapper.readValue(defaultMapper.writeValueAsString(game), Game.class);

        assertThat(back.getVersion()).as("default mapper drops the @JsonIgnore version").isNull();
    }

    @Test
    @DisplayName("per-player betting-round flags (hasActed/betAmount/…) survive the hot-state round-trip")
    void hotStateMapperPreservesBettingRoundFlags() throws Exception {
        Game game = sampleGame();
        Player p = game.getPlayers().get(0);
        p.setHasActed(true);
        p.setBetAmount(40);
        p.setTotalBetInRound(60);
        p.setAllIn(true);

        Player back = hotStateMapper.readValue(hotStateMapper.writeValueAsString(game), Game.class)
                .getPlayers().get(0);

        // Regression guard: Player.hasActed has a non-standard getter (hasActed(), not isHasActed/getHasActed),
        // so before its explicit @JsonProperty it was silently dropped from this JSON. Every reload then reset it
        // to false, isBettingRoundComplete() never fired, and the betting round never ended — the flop never came
        // and players cycled forever. Every per-round flag must round-trip.
        assertThat(back.hasActed()).as("hasActed must survive — its loss froze the betting round").isTrue();
        assertThat(back.getBetAmount()).isEqualTo(40);
        assertThat(back.getTotalBetInRound()).isEqualTo(60);
        assertThat(back.isAllIn()).isTrue();
    }

    @Test
    @DisplayName("a full betting round survives per-action hot-state reconstitution → the flop is dealt")
    void bettingRoundCompletesAcrossHotStateReconstitution() throws Exception {
        // Replicates the live per-request cycle: act on the aggregate → apply to the Game entity → serialize to the
        // hot-state JSON → read it back → reconstitute a fresh aggregate for the next action. This is the exact path
        // the dropped hasActed lived on: in-memory captureState/reconstitute hid it (the flag survives in memory);
        // only the JSON round-trip exposed it. With the flag lost this loop never leaves PRE_FLOP.
        PokerGameMapper bridge = new PokerGameMapper();
        PokerGame aggregate = PokerGame.create(List.of(
                new PlayerInfo("Alice", 1000, false),
                new PlayerInfo("Bob", 1000, false),
                new PlayerInfo("Carol", 1000, false)), Chips.of(10), Chips.of(20));
        aggregate.startNewHand();

        Game game = new Game();
        game.setId(aggregate.getId());
        game.setHandLifecycleState(HandLifecycleState.IN_PROGRESS);

        int guard = 0;
        while (aggregate.getPhase() == GamePhase.PRE_FLOP && !aggregate.isFinished() && guard++ < 12) {
            Player current = aggregate.getCurrentPlayer();
            int toCall = aggregate.getCurrentBet().amount() - current.getBetAmount();
            aggregate.executeAction(current.getId(),
                    toCall > 0 ? PlayerAction.CALL : PlayerAction.CHECK, null);

            // one REST-request boundary: persist aggregate → hot-state JSON → reload → reconstitute
            bridge.applyToGame(aggregate, game);
            game = hotStateMapper.readValue(hotStateMapper.writeValueAsString(game), Game.class);
            aggregate = bridge.fromGame(game);
        }

        assertThat(aggregate.getPhase())
                .as("pre-flop betting must complete across reconstitution and deal the flop")
                .isEqualTo(GamePhase.FLOP);
        assertThat(aggregate.getCommunityCards()).as("flop = 3 community cards").hasSize(3);
    }

    private static void setVersion(Game game, long version) throws Exception {
        var field = Game.class.getDeclaredField("version");
        field.setAccessible(true);
        field.set(game, version);
    }
}
