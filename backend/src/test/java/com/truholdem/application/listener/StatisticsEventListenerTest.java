package com.truholdem.application.listener;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.truholdem.domain.event.HandCompleted;
import com.truholdem.domain.event.PlayerActed;
import com.truholdem.domain.event.PotAwarded;
import com.truholdem.domain.value.Chips;
import com.truholdem.domain.value.Pot;
import com.truholdem.model.GamePhase;
import com.truholdem.service.PlayerStatisticsService;

@ExtendWith(MockitoExtension.class)
@DisplayName("StatisticsEventListener — event-driven statistics")
class StatisticsEventListenerTest {

    @Mock
    private PlayerStatisticsService statisticsService;

    @InjectMocks
    private StatisticsEventListener listener;

    private final UUID gameId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();

    @Test
    @DisplayName("PlayerActed (BET, not all-in) → recordAction only")
    void playerActedBetRecordsAction() {
        PlayerActed event = new PlayerActed(gameId, playerId, "Hero",
                PlayerActed.ActionType.BET, Chips.of(50), GamePhase.FLOP,
                Chips.of(80), Chips.of(950));

        listener.onPlayerActed(event);

        verify(statisticsService).recordAction("Hero", "BET");
        verify(statisticsService, never()).recordAllIn("Hero");
    }

    @Test
    @DisplayName("PlayerActed all-in → recordAction + recordAllIn")
    void playerActedAllInRecordsAllIn() {
        PlayerActed event = new PlayerActed(gameId, playerId, "Hero",
                PlayerActed.ActionType.ALL_IN, Chips.of(1000), GamePhase.TURN,
                Chips.of(1200), Chips.zero(), true);

        listener.onPlayerActed(event);

        verify(statisticsService).recordAction("Hero", "ALL_IN");
        verify(statisticsService).recordAllIn("Hero");
    }

    @Test
    @DisplayName("HandCompleted at showdown → recordWin + recordShowdown per pot")
    void handCompletedShowdownRecordsWinAndShowdown() {
        HandCompleted event = new HandCompleted(gameId, 1,
                List.of(new HandCompleted.PotResult(playerId, "Hero", Chips.of(300), "Flush", false)),
                Map.of(playerId, Chips.of(1300)),
                Duration.ofSeconds(60), true);

        listener.onHandCompleted(event);

        verify(statisticsService).recordWin("Hero", 300);
        verify(statisticsService).recordShowdown("Hero", true);
    }

    @Test
    @DisplayName("HandCompleted without showdown → recordWin only")
    void handCompletedNoShowdownRecordsWinOnly() {
        HandCompleted event = new HandCompleted(gameId, 2,
                List.of(new HandCompleted.PotResult(playerId, "Hero", Chips.of(120), null, false)),
                Map.of(playerId, Chips.of(1120)),
                Duration.ofSeconds(30), false);

        listener.onHandCompleted(event);

        verify(statisticsService).recordWin("Hero", 120);
        verify(statisticsService, never()).recordShowdown("Hero", true);
    }

    @Test
    @DisplayName("PotAwarded does NOT record a win (avoids double counting with HandCompleted)")
    void potAwardedDoesNotDoubleCount() {
        PotAwarded event = new PotAwarded(gameId, playerId, "Hero",
                Chips.of(300), "Flush", Pot.PotType.MAIN);

        listener.onPotAwarded(event);

        verifyNoMoreInteractions(statisticsService);
    }
}
