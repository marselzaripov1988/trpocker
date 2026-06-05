package com.truholdem.service;

import com.truholdem.config.AppProperties;
import com.truholdem.domain.event.TournamentStarted;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentStatus;
import com.truholdem.model.TournamentType;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;
import com.truholdem.service.tournament.TournamentTimingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TournamentStartService Tests")
class TournamentStartServiceTest {

    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentRegistrationRepository registrationRepository;
    @Mock
    private TournamentTableRepository tableRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private AppProperties appProperties;
    @Mock
    private AppProperties.Tournament tournamentProperties;
    @Mock
    private TournamentTimingService timingService;
    @Mock
    private com.truholdem.service.cluster.TableOwnershipService ownership;
    @Mock
    private com.truholdem.repository.PyramidBuyoutRepository buyoutRepository;
    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private TournamentStartService tournamentStartService;

    @BeforeEach
    void setUp() {
        when(appProperties.getTournament()).thenReturn(tournamentProperties);
        when(tournamentProperties.getStartBatchSize()).thenReturn(200);
        when(tableRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        doReturn(scheduledFuture).when(taskScheduler)
                .scheduleAtFixedRate(any(Runnable.class), any(), any());
        when(ownership.acquire(any())).thenReturn(true);
        lenient().when(ownership.isOwner(any())).thenReturn(true);

        tournamentStartService = new TournamentStartService(
                tournamentRepository,
                registrationRepository,
                tableRepository,
                eventPublisher,
                taskScheduler,
                appProperties,
                timingService,
                ownership,
                buyoutRepository);
    }

    @Test
    @DisplayName("should batch-create tables and seat players on start")
    void shouldStartLargeFieldInBatches() {
        UUID tournamentId = UUID.randomUUID();
        Tournament tournament = Tournament.builder("MTT")
                .type(TournamentType.MULTI_TABLE)
                .players(2, 10_000)
                .buyIn(10)
                .build();
        setTournamentId(tournament, tournamentId);

        List<UUID> players = java.util.stream.IntStream.range(0, 18)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(registrationRepository.countByTournamentId(tournamentId)).thenReturn(18);
        when(registrationRepository.findPlayerIdsForSeating(tournamentId)).thenReturn(players);
        when(tournamentRepository.save(any(Tournament.class))).thenAnswer(inv -> inv.getArgument(0));

        TournamentStartResult result = tournamentStartService.completeStart(tournamentId);

        assertThat(result.tableCount()).isEqualTo(3);
        assertThat(result.playerCount()).isEqualTo(18);
        assertThat(tournament.getStatus()).isEqualTo(TournamentStatus.RUNNING);
        verify(registrationRepository).markAllAsPlaying(tournamentId, tournament.getStartingChips());
        verify(tableRepository, atLeastOnce()).saveAll(anyList());
        verify(eventPublisher).publishEvent(any(TournamentStarted.class));
    }

    @Test
    @DisplayName("should use async threshold from configuration")
    void shouldRespectAsyncThreshold() {
        TournamentStartService service = new TournamentStartService(
                tournamentRepository,
                registrationRepository,
                tableRepository,
                eventPublisher,
                taskScheduler,
                appProperties,
                timingService,
                ownership,
                buyoutRepository);
        when(tournamentProperties.getAsyncStartThreshold()).thenReturn(100);
        assertThat(service.shouldStartAsynchronously(99)).isFalse();
        assertThat(service.shouldStartAsynchronously(100)).isTrue();
    }

    private static void setTournamentId(Tournament tournament, UUID id) {
        try {
            var field = Tournament.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(tournament, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
