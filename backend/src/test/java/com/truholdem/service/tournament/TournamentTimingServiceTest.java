package com.truholdem.service.tournament;

import com.truholdem.config.AppProperties;
import com.truholdem.model.BlindStructure;
import com.truholdem.model.Tournament;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentTimingService Tests")
class TournamentTimingServiceTest {

    @Mock
    private AppProperties appProperties;
    @Mock
    private AppProperties.Tournament tournamentProperties;

    private TournamentTimingService timingService;

    @BeforeEach
    void setUp() {
        when(appProperties.getTournament()).thenReturn(tournamentProperties);
        timingService = new TournamentTimingService(appProperties);
    }

    @Test
    @DisplayName("uses blind structure minutes when no override")
    void usesBlindStructureDuration() {
        when(tournamentProperties.getLevelDurationSeconds()).thenReturn(0);
        Tournament tournament = Tournament.builder("Test")
                .blindStructure(BlindStructure.turbo())
                .build();
        tournament.markRunningAtStart();
        setLevelStartTime(tournament, Instant.now().minusSeconds(60));

        assertThat(timingService.levelDuration(tournament)).isEqualTo(Duration.ofMinutes(5));
        assertThat(timingService.secondsToNextLevel(tournament)).isLessThanOrEqualTo(240);
    }

    @Test
    @DisplayName("uses seconds override when configured")
    void usesSecondsOverride() {
        when(tournamentProperties.getLevelDurationSeconds()).thenReturn(5);
        Tournament tournament = Tournament.builder("Test")
                .build();
        tournament.markRunningAtStart();
        setLevelStartTime(tournament, Instant.now().minusSeconds(2));

        assertThat(timingService.levelDuration(tournament)).isEqualTo(Duration.ofSeconds(5));
        assertThat(timingService.secondsToNextLevel(tournament)).isBetween(1L, 4L);
    }

    private static void setLevelStartTime(Tournament tournament, Instant instant) {
        try {
            var field = Tournament.class.getDeclaredField("levelStartTime");
            field.setAccessible(true);
            field.set(tournament, instant);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
