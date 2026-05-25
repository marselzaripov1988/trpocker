package com.truholdem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.model.TournamentTable;
import com.truholdem.model.TournamentType;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;
import com.truholdem.service.tournament.TournamentTimingService;

@ExtendWith(MockitoExtension.class)
@DisplayName("Tournament detail (5a) Tests")
class TournamentDetailServiceTest {

    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentRegistrationRepository registrationRepository;
    @Mock
    private TournamentTableRepository tableRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private TournamentStartService tournamentStartService;
    @Mock
    private AppProperties appProperties;
    @Mock
    private AppProperties.Tournament tournamentProperties;
    @Mock
    private TournamentTimingService timingService;

    private TournamentService tournamentService;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        when(appProperties.getTournament()).thenReturn(tournamentProperties);
        when(tournamentProperties.getDetailMaxRegistrations()).thenReturn(500);
        when(tournamentProperties.getDetailMaxTables()).thenReturn(100);

        tournamentService = new TournamentService(
                tournamentRepository,
                registrationRepository,
                tableRepository,
                eventPublisher,
                tournamentStartService,
                appProperties,
                timingService);

        tournamentId = UUID.randomUUID();
    }

    @Test
    @DisplayName("getTournamentDetail includes players and table seats")
    void shouldIncludePlayersAndTableSeats() {
        Tournament tournament = Tournament.builder("Detail SNG")
                .type(TournamentType.SIT_AND_GO)
                .players(2, 4)
                .buyIn(10)
                .build();
        setTournamentId(tournament, tournamentId);
        tournament.markRunningAtStart();

        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        TournamentRegistration reg1 = new TournamentRegistration(tournament, player1, "Alice");
        TournamentRegistration reg2 = new TournamentRegistration(tournament, player2, "Bob");
        reg1.startPlaying();
        reg2.startPlaying();

        TournamentTable table = new TournamentTable(tournament, 1);
        setTableId(table, UUID.randomUUID());
        table.seatPlayer(player1);
        table.seatPlayer(player2);

        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(registrationRepository.countByTournamentId(tournamentId)).thenReturn(2);
        when(registrationRepository.countActiveByTournamentId(tournamentId)).thenReturn(2);
        when(registrationRepository.sumActiveChipsByTournamentId(tournamentId)).thenReturn(3000L);
        when(registrationRepository.findByTournamentIdOrderByChipsDesc(tournamentId))
                .thenReturn(List.of(reg1, reg2));
        when(registrationRepository.findByTournamentIdOrderByChipsDesc(eq(tournamentId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reg1)));
        when(tableRepository.countActiveTablesByTournament(tournamentId)).thenReturn(1);
        when(tableRepository.findActiveTablesByTournament(tournamentId)).thenReturn(List.of(table));

        var detail = tournamentService.getTournamentDetail(tournamentId);

        assertThat(detail.players()).hasSize(2);
        assertThat(detail.players().get(0).playerName()).isEqualTo("Alice");
        assertThat(detail.tables()).hasSize(1);
        assertThat(detail.tables().get(0).players()).hasSize(2);
        assertThat(detail.tables().get(0).players())
                .extracting(p -> p.name())
                .containsExactlyInAnyOrder("Alice", "Bob");
    }

    private static void setTournamentId(Tournament tournament, UUID id) {
        try {
            var field = Tournament.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(tournament, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setTableId(TournamentTable table, UUID id) {
        try {
            var field = TournamentTable.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(table, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
