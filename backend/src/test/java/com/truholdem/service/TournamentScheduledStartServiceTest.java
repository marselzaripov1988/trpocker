package com.truholdem.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.config.AppProperties;

@DisplayName("TournamentScheduledStartService (auto-start due tournaments, min-players gated)")
class TournamentScheduledStartServiceTest {

    private final TournamentService tournamentService = mock(TournamentService.class);

    private AppProperties props(boolean enabled) {
        AppProperties p = new AppProperties();
        p.getTournament().setScheduledStartEnabled(enabled);
        return p;
    }

    private TournamentScheduledStartService service(AppProperties props) {
        return new TournamentScheduledStartService(tournamentService, props);
    }

    @Test
    @DisplayName("disabled flag → no work")
    void disabledIsInert() {
        service(props(false)).startDueTournaments();
        verify(tournamentService, never()).dueForScheduledStart(any());
        verifyNoInteractions(tournamentService);
    }

    @Test
    @DisplayName("starts a due tournament that meets minPlayers; skips one that does not")
    void startsOnlyWhenEnoughPlayers() {
        UUID enough = UUID.randomUUID();
        UUID tooFew = UUID.randomUUID();
        when(tournamentService.dueForScheduledStart(any())).thenReturn(List.of(enough, tooFew));
        when(tournamentService.registeredCount(enough)).thenReturn(5);
        when(tournamentService.minPlayers(enough)).thenReturn(2);
        when(tournamentService.registeredCount(tooFew)).thenReturn(1);
        when(tournamentService.minPlayers(tooFew)).thenReturn(2);

        service(props(true)).startDueTournaments();

        verify(tournamentService).startTournament(enough);
        verify(tournamentService, never()).startTournament(tooFew);
    }

    @Test
    @DisplayName("a failing start does not abort the rest of the sweep")
    void oneFailureDoesNotStopOthers() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(tournamentService.dueForScheduledStart(any())).thenReturn(List.of(bad, good));
        when(tournamentService.registeredCount(any())).thenReturn(9);
        when(tournamentService.minPlayers(any())).thenReturn(2);
        // startTournament is void → stub to throw for `bad`
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(tournamentService).startTournament(bad);

        service(props(true)).startDueTournaments();

        verify(tournamentService).startTournament(bad);
        verify(tournamentService).startTournament(good); // still attempted
    }

    @Test
    @DisplayName("full-required: starts when the table is full")
    void fullRequiredStartsWhenFull() {
        UUID id = UUID.randomUUID();
        when(tournamentService.dueForScheduledStart(any())).thenReturn(List.of(id));
        when(tournamentService.requiresFullToStart(id)).thenReturn(true);
        when(tournamentService.registeredCount(id)).thenReturn(9);
        when(tournamentService.maxPlayers(id)).thenReturn(9);

        service(props(true)).startDueTournaments();

        verify(tournamentService).startTournament(id);
        verify(tournamentService, never()).postponeToNextDay(id);
    }

    @Test
    @DisplayName("full-required: postpones to next day when under-filled")
    void fullRequiredPostponesWhenNotFull() {
        UUID id = UUID.randomUUID();
        when(tournamentService.dueForScheduledStart(any())).thenReturn(List.of(id));
        when(tournamentService.requiresFullToStart(id)).thenReturn(true);
        when(tournamentService.registeredCount(id)).thenReturn(5);
        when(tournamentService.maxPlayers(id)).thenReturn(9);

        service(props(true)).startDueTournaments();

        verify(tournamentService).postponeToNextDay(id);
        verify(tournamentService, never()).startTournament(id);
    }

    @Test
    @DisplayName("passes a sensible now to the due-query")
    void queriesWithNow() {
        when(tournamentService.dueForScheduledStart(any())).thenReturn(List.of());
        service(props(true)).startDueTournaments();
        verify(tournamentService).dueForScheduledStart(any(Instant.class));
    }
}
