package com.truholdem.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.truholdem.dto.HandHistoryResponse;
import com.truholdem.model.HandHistory;

/**
 * Wire-format contract for hand history. The {@link HandHistoryResponse} read DTO replaced the raw
 * {@link HandHistory} JPA entity at the controller boundary (Phase 3, CQRS read side); this pins that
 * the DTO serializes to the <em>identical</em> JSON the Angular client already consumes, so the
 * decoupling cannot silently change the wire shape.
 */
@DisplayName("HandHistory JSON wire-format contract")
class HandHistoryJsonContractTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    private HandHistory sampleHistory() {
        HandHistory h = new HandHistory();
        h.setId(UUID.fromString("00000000-0000-0000-0000-0000000000f1"));
        h.setGameId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        h.setHandNumber(7);
        h.setPlayedAt(LocalDateTime.of(2026, 6, 1, 12, 30, 0));
        h.setSmallBlind(10);
        h.setBigBlind(20);
        h.setDealerPosition(2);
        h.setWinnerName("Hero");
        h.setWinningHandDescription("Full House, Kings over Tens");
        h.setFinalPot(450);

        HandHistory.HandHistoryPlayer player = new HandHistory.HandHistoryPlayer();
        player.setPlayerId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        player.setPlayerName("Hero");
        player.setStartingChips(1000);
        player.setSeatPosition(0);
        player.setHoleCard1Suit("SPADES");
        player.setHoleCard1Value("ACE");
        player.setHoleCard2Suit("HEARTS");
        player.setHoleCard2Value("KING");
        h.setPlayers(List.of(player));

        h.setActions(List.of(new HandHistory.ActionRecord(
                player.getPlayerId(), "Hero", "RAISE", 100, "PRE_FLOP",
                LocalDateTime.of(2026, 6, 1, 12, 30, 5))));

        h.setBoard(List.of(
                new HandHistory.CardRecord("SPADES", "QUEEN"),
                new HandHistory.CardRecord("CLUBS", "JACK")));
        return h;
    }

    @Test
    @DisplayName("HandHistoryResponse serializes identically to the HandHistory entity")
    void viewMatchesEntityJson() {
        HandHistory entity = sampleHistory();

        JsonNode entityJson = mapper.valueToTree(entity);
        JsonNode viewJson = mapper.valueToTree(HandHistoryResponse.from(entity));

        assertEquals(entityJson, viewJson);
    }

    @Test
    @DisplayName("top-level and nested field names match the frontend contract")
    void fieldNamesAreStable() {
        JsonNode json = mapper.valueToTree(HandHistoryResponse.from(sampleHistory()));

        for (String field : List.of("id", "gameId", "handNumber", "playedAt", "smallBlind", "bigBlind",
                "dealerPosition", "winnerName", "winningHandDescription", "finalPot",
                "players", "actions", "board")) {
            assertTrue(json.has(field), "missing top-level field: " + field);
        }

        JsonNode player = json.get("players").get(0);
        for (String field : List.of("playerId", "playerName", "startingChips", "seatPosition",
                "holeCard1Suit", "holeCard1Value", "holeCard2Suit", "holeCard2Value")) {
            assertTrue(player.has(field), "missing player field: " + field);
        }

        JsonNode action = json.get("actions").get(0);
        for (String field : List.of("playerId", "playerName", "action", "amount", "phase", "timestamp")) {
            assertTrue(action.has(field), "missing action field: " + field);
        }

        JsonNode card = json.get("board").get(0);
        assertTrue(card.has("suit"), "missing board card field: suit");
        assertTrue(card.has("value"), "missing board card field: value");
    }
}
