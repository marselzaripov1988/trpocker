package com.truholdem.service.game;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.GameStateRedisConfig;
import com.truholdem.model.Card;
import com.truholdem.model.Game;
import com.truholdem.model.Player;
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
    @DisplayName("the hot-state mapper preserves the full state including the deck")
    void hotStateMapperPreservesDeck() throws Exception {
        Game game = sampleGame();
        Game back = hotStateMapper.readValue(hotStateMapper.writeValueAsString(game), Game.class);

        // The dedicated hot-state mapper re-exposes the deck so a Redis cache hit returns a fully playable game
        // (the next street can be dealt). Hole cards and community state continue to round-trip too.
        assertThat(back.getDeck()).as("hot-state mapper keeps the deck")
                .containsExactly(new Card(Suit.CLUBS, Value.TWO), new Card(Suit.DIAMONDS, Value.SEVEN));
        assertThat(back.getPlayers().get(0).getHand()).hasSize(2);
    }
}
