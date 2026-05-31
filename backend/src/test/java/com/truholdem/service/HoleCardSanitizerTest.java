package com.truholdem.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.truholdem.model.Card;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.Suit;
import com.truholdem.model.Value;

@DisplayName("HoleCardSanitizer")
class HoleCardSanitizerTest {

    private HoleCardSanitizer sanitizer;
    private Player hero;
    private Player villain;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        sanitizer = new HoleCardSanitizer(mapper);

        hero = new Player("Hero", 1000, false);
        hero.setId(UUID.randomUUID());
        hero.setSeatPosition(0);
        hero.getHand().add(new Card(Suit.SPADES, Value.ACE));
        hero.getHand().add(new Card(Suit.HEARTS, Value.KING));

        villain = new Player("Villain", 1000, true);
        villain.setId(UUID.randomUUID());
        villain.setSeatPosition(1);
        villain.getHand().add(new Card(Suit.CLUBS, Value.TWO));
        villain.getHand().add(new Card(Suit.DIAMONDS, Value.SEVEN));
    }

    private Game gameAt(GamePhase phase) {
        Game game = new Game();
        game.setId(UUID.randomUUID());
        game.setPhase(phase);
        game.addPlayer(hero);
        game.addPlayer(villain);
        return game;
    }

    private JsonNode handFor(JsonNode root, UUID playerId) {
        for (JsonNode player : root.get("players")) {
            if (playerId.toString().equals(player.get("id").asText())) {
                return player.get("hand");
            }
        }
        throw new AssertionError("player not found: " + playerId);
    }

    private void assertMasked(JsonNode hand) {
        assertEquals(2, hand.size(), "card count must be preserved");
        for (JsonNode card : hand) {
            assertTrue(card.get("suit").isNull(), "masked card must not expose suit");
            assertTrue(card.get("value").isNull(), "masked card must not expose value");
        }
    }

    private void assertRevealed(JsonNode hand) {
        assertEquals(2, hand.size());
        assertFalse(hand.get(0).get("suit").isNull(), "revealed card keeps its suit");
        assertFalse(hand.get(0).get("value").isNull(), "revealed card keeps its value");
    }

    @Test
    @DisplayName("reveals the viewer's own seats, masks the rest pre-showdown")
    void masksOpponentsForViewer() {
        JsonNode root = sanitizer.sanitize(gameAt(GamePhase.FLOP), Set.of(hero.getId()));

        assertRevealed(handFor(root, hero.getId()));
        assertMasked(handFor(root, villain.getId()));
    }

    @Test
    @DisplayName("masks everyone when the viewer owns no seat (broadcast)")
    void masksAllForBroadcast() {
        JsonNode root = sanitizer.sanitize(gameAt(GamePhase.TURN), Set.of());

        assertMasked(handFor(root, hero.getId()));
        assertMasked(handFor(root, villain.getId()));
    }

    @Test
    @DisplayName("reveals non-folded hands at showdown even without ownership")
    void revealsNonFoldedAtShowdown() {
        JsonNode root = sanitizer.sanitize(gameAt(GamePhase.SHOWDOWN), Set.of());

        assertRevealed(handFor(root, hero.getId()));
        assertRevealed(handFor(root, villain.getId()));
    }

    @Test
    @DisplayName("keeps a folded player's cards hidden at showdown")
    void keepsFoldedHiddenAtShowdown() {
        villain.fold();
        JsonNode root = sanitizer.sanitize(gameAt(GamePhase.SHOWDOWN), Set.of());

        assertRevealed(handFor(root, hero.getId()));
        assertMasked(handFor(root, villain.getId()));
    }

    @Test
    @DisplayName("never serializes the deck")
    void deckNeverSerialized() {
        JsonNode root = sanitizer.sanitize(gameAt(GamePhase.FLOP), Set.of(hero.getId()));
        assertFalse(root.has("deck"), "deck must stay hidden (already @JsonIgnore on the entity)");
    }
}
