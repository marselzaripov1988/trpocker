package com.truholdem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.truholdem.domain.event.DomainEvent;
import com.truholdem.domain.event.GameCreated;
import com.truholdem.domain.event.GameStarted;
import com.truholdem.domain.event.HandCompleted;
import com.truholdem.domain.event.PhaseChanged;
import com.truholdem.domain.event.PlayerActed;
import com.truholdem.domain.event.PlayerEliminated;
import com.truholdem.domain.event.PotAwarded;
import com.truholdem.domain.value.Chips;
import com.truholdem.domain.value.Pot;
import com.truholdem.dto.GameEventLogResponse;
import com.truholdem.model.Card;
import com.truholdem.model.GameEventLog;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Suit;
import com.truholdem.model.Value;
import com.truholdem.repository.GameEventLogRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("GameEventLogService — append-only event log + replay")
class GameEventLogServiceTest {

    @Mock
    private GameEventLogRepository repository;

    @Captor
    private ArgumentCaptor<GameEventLog> rowCaptor;

    private GameEventLogService service;

    private final JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final UUID gameId = UUID.randomUUID();
    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GameEventLogService(repository, mapper);
    }

    @Test
    @DisplayName("records events in publication order, stamping the current hand number")
    void recordsEventsWithStampedHandNumber() {
        service.record(new GameStarted(gameId, 0, p1, p2, 3));
        service.record(new PlayerActed(gameId, p1, "Hero", PlayerActed.ActionType.CALL,
                Chips.of(20), GamePhase.PRE_FLOP, Chips.of(60), Chips.of(980)));
        service.record(new HandCompleted(gameId, 3,
                List.of(new HandCompleted.PotResult(p1, "Hero", Chips.of(120), "Pair", false)),
                Map.of(p1, Chips.of(1100), p2, Chips.of(900)),
                Duration.ofSeconds(45), 6, true));

        verify(repository, org.mockito.Mockito.times(3)).save(rowCaptor.capture());
        List<GameEventLog> rows = rowCaptor.getAllValues();

        assertThat(rows).extracting(GameEventLog::getEventType)
                .containsExactly("GameStarted", "PlayerActed", "HandCompleted");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getGameId()).isEqualTo(gameId);
            assertThat(r.getHandNumber()).isEqualTo(3);
            assertThat(r.getPayload()).isNotBlank();
            assertThat(r.getEventId()).isNotNull();
        });
    }

    @Test
    @DisplayName("PlayerActed payload carries the action fields")
    void playerActedPayloadHasActionFields() throws Exception {
        service.record(new GameStarted(gameId, 0, p1, p2, 1));
        service.record(new PlayerActed(gameId, p1, "Hero", PlayerActed.ActionType.RAISE,
                Chips.of(100), GamePhase.FLOP, Chips.of(250), Chips.of(800), false));

        verify(repository, org.mockito.Mockito.times(2)).save(rowCaptor.capture());
        GameEventLog acted = rowCaptor.getAllValues().get(1);
        var json = mapper.readTree(acted.getPayload());

        assertThat(json.get("playerName").asText()).isEqualTo("Hero");
        assertThat(json.get("action").asText()).isEqualTo("RAISE");
        assertThat(json.get("amount").get("amount").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("every domain-event type serializes to JSON without error")
    void serializesAllEventTypes() {
        List<DomainEvent> events = List.of(
                new GameCreated(gameId, List.of(p1, p2), Chips.of(1000), Chips.of(10), Chips.of(20)),
                new GameStarted(gameId, 0, p1, p2, 1),
                new PlayerActed(gameId, p1, "Hero", PlayerActed.ActionType.ALL_IN,
                        Chips.of(800), GamePhase.TURN, Chips.of(1200), Chips.zero(), true),
                new PhaseChanged(gameId, GamePhase.PRE_FLOP, GamePhase.FLOP,
                        List.of(new Card(Suit.HEARTS, Value.ACE)),
                        List.of(new Card(Suit.HEARTS, Value.ACE)), Chips.of(100), 2),
                new PotAwarded(gameId, p1, "Hero", Chips.of(300), "Flush", Pot.PotType.MAIN),
                new HandCompleted(gameId, 1,
                        List.of(new HandCompleted.PotResult(p1, "Hero", Chips.of(300), "Flush", false)),
                        Map.of(p1, Chips.of(1300)), Duration.ofSeconds(30), 4, true),
                new PlayerEliminated(gameId, p2, "Villain", 2, Chips.of(0), 12));

        events.forEach(service::record);

        verify(repository, org.mockito.Mockito.times(events.size())).save(any(GameEventLog.class));
    }

    @Test
    @DisplayName("eventsForHand returns the ordered betting narrative reconstructed from the log")
    void eventsForHandReplaysNarrative() {
        List<GameEventLog> stored = List.of(
                row(1, "GameStarted", new GameStarted(gameId, 0, p1, p2, 5)),
                row(2, "PlayerActed", new PlayerActed(gameId, p1, "Hero", PlayerActed.ActionType.CALL,
                        Chips.of(20), GamePhase.PRE_FLOP, Chips.of(60), Chips.of(980))),
                row(3, "HandCompleted", new HandCompleted(gameId, 5,
                        List.of(new HandCompleted.PotResult(p1, "Hero", Chips.of(120), "Pair", false)),
                        Map.of(p1, Chips.of(1100)), Duration.ofSeconds(40), 5, true)));
        when(repository.findByGameIdAndHandNumberOrderBySeqNoAsc(gameId, 5)).thenReturn(stored);

        List<GameEventLogResponse> replay = service.eventsForHand(gameId, 5);

        assertThat(replay).extracting(GameEventLogResponse::eventType)
                .containsExactly("GameStarted", "PlayerActed", "HandCompleted");
        assertThat(replay).extracting(GameEventLogResponse::seqNo).containsExactly(1L, 2L, 3L);
        // payload is exposed as parsed JSON, not an escaped string
        assertThat(replay.get(1).payload().get("action").asText()).isEqualTo("CALL");
    }

    private GameEventLog row(long seqNo, String type, DomainEvent event) {
        GameEventLog row = new GameEventLog(event.getEventId(), gameId,
                type.equals("HandCompleted") ? 5 : 5, type, event.getOccurredAt(), serialize(event));
        row.setSeqNo(seqNo);
        return row;
    }

    private String serialize(DomainEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
