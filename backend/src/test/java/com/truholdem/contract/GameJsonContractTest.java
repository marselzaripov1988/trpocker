package com.truholdem.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.truholdem.dto.GameUpdateMessage;
import com.truholdem.model.Card;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
import com.truholdem.model.GameUpdateType;
import com.truholdem.model.HandLifecycleState;
import com.truholdem.model.Player;
import com.truholdem.model.Suit;
import com.truholdem.model.Value;

/**
 * Wire-format contract (snapshot) tests for the JSON the backend hands to the
 * Angular client over REST and WebSocket. These pin the field names and key
 * shapes the frontend depends on, so an accidental rename during the planned
 * engine migration fails fast instead of silently breaking the UI.
 *
 * <p>The {@code Game} entity is serialized directly because both the REST
 * controllers and {@code GameNotificationService} broadcast it as-is today.
 * The {@code deck}/{@code hand} fields are intentionally asserted as present:
 * they document the current card-leakage contract that Phase 3 of the migration
 * is expected to remove (at which point these expectations must be updated on
 * purpose, signalling the contract change).
 */
@DisplayName("Game JSON wire-format contract")
class GameJsonContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private Game sampleGame() {
        Game game = new Game();
        game.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        game.setPhase(GamePhase.FLOP);
        game.setHandLifecycleState(HandLifecycleState.IN_PROGRESS);
        game.setCurrentPot(150);
        game.setCurrentBet(50);
        game.setSmallBlind(10);
        game.setBigBlind(20);

        Player hero = new Player("Hero", 1000, false);
        hero.setId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        hero.setSeatPosition(0);
        hero.getHand().add(new Card(Suit.SPADES, Value.ACE));
        hero.getHand().add(new Card(Suit.HEARTS, Value.KING));

        Player villain = new Player("Villain", 980, true);
        villain.setId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"));
        villain.setSeatPosition(1);

        game.addPlayer(hero);
        game.addPlayer(villain);
        game.addCommunityCard(new Card(Suit.CLUBS, Value.TWO));

        return game;
    }

    @Nested
    @DisplayName("Game entity payload")
    class GamePayload {

        @Test
        @DisplayName("Exposes the stable top-level field names the client reads")
        void topLevelContract() throws Exception {
            JsonNode json = mapper.valueToTree(sampleGame());

            for (String field : List.of(
                    "id", "phase", "handLifecycleState", "players", "communityCards",
                    "currentPot", "currentBet", "smallBlind", "bigBlind",
                    "dealerPosition", "handNumber", "winnerIds", "sidePots")) {
                assertTrue(json.has(field), "missing contract field: " + field);
            }

            assertEquals("FLOP", json.get("phase").asText());
            assertEquals("IN_PROGRESS", json.get("handLifecycleState").asText());
            assertEquals(150, json.get("currentPot").asInt());
            assertEquals(50, json.get("currentBet").asInt());
            assertTrue(json.get("players").isArray());
            assertEquals(2, json.get("players").size());
            assertTrue(json.get("communityCards").isArray());
            assertEquals(1, json.get("communityCards").size());
        }

        @Test
        @DisplayName("Finished flag is serialized as 'isFinished' (not 'finished')")
        void finishedFlagName() throws Exception {
            JsonNode json = mapper.valueToTree(sampleGame());

            assertTrue(json.has("isFinished"), "client relies on 'isFinished'");
            assertFalse(json.has("finished"), "must not expose bare 'finished'");
            assertFalse(json.get("isFinished").asBoolean());
        }

        @Test
        @DisplayName("Internal-only fields are not leaked into the payload")
        void hidesInternalFields() throws Exception {
            JsonNode json = mapper.valueToTree(sampleGame());

            assertFalse(json.has("version"), "@JsonIgnore version must stay internal");
        }

        @Test
        @DisplayName("Player object keeps its stable field names")
        void playerContract() throws Exception {
            JsonNode hero = mapper.valueToTree(sampleGame()).get("players").get(0);

            for (String field : List.of(
                    "id", "name", "chips", "betAmount", "folded", "isBot",
                    "isAllIn", "seatPosition", "hand")) {
                assertTrue(hero.has(field), "missing player contract field: " + field);
            }

            assertEquals("Hero", hero.get("name").asText());
            assertEquals(1000, hero.get("chips").asInt());
            assertFalse(hero.get("folded").asBoolean());
            assertFalse(hero.get("isBot").asBoolean());
        }

        @Test
        @DisplayName("Known card-leakage contract (deck + hands) — pinned until Phase 3 removes it")
        void documentsCurrentCardExposure() throws Exception {
            JsonNode json = mapper.valueToTree(sampleGame());

            assertTrue(json.has("deck"), "deck currently leaks; Phase 3 must remove and update this test");
            assertTrue(json.get("players").get(0).get("hand").isArray());
            assertEquals(2, json.get("players").get(0).get("hand").size());
        }
    }

    @Nested
    @DisplayName("WebSocket envelope")
    class WebSocketEnvelope {

        @Test
        @DisplayName("GameUpdateMessage keeps its envelope keys")
        void envelopeContract() throws Exception {
            GameUpdateMessage message = new GameUpdateMessage(
                    GameUpdateType.GAME_STATE.name(), sampleGame(), "update", 1_700_000_000L);

            JsonNode json = mapper.valueToTree(message);

            for (String field : List.of("type", "gameState", "message", "timestamp")) {
                assertTrue(json.has(field), "missing envelope field: " + field);
            }
            assertEquals("GAME_STATE", json.get("type").asText());
            assertEquals(1_700_000_000L, json.get("timestamp").asLong());
            assertTrue(json.get("gameState").has("id"), "envelope wraps the full game state");
        }

        @Test
        @DisplayName("GameUpdateType names are part of the client contract")
        void updateTypeNames() {
            List<String> names = java.util.Arrays.stream(GameUpdateType.values())
                    .map(Enum::name)
                    .toList();

            assertTrue(names.containsAll(List.of(
                    "GAME_STATE", "PLAYER_ACTION", "PHASE_CHANGE", "SHOWDOWN")),
                    "core update types must remain stable for the client");
        }
    }
}
