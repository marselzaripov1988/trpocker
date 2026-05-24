package com.truholdem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.truholdem.model.BlindLevel;
import com.truholdem.model.Game;
import com.truholdem.model.PlayerInfo;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.model.TournamentStatus;
import com.truholdem.model.TournamentTable;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentTableRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentTableGameService Tests")
class TournamentTableGameServiceTest {

    @Mock
    private TournamentTableRepository tableRepository;

    @Mock
    private TournamentRegistrationRepository registrationRepository;

    @Mock
    private PokerGameService pokerGameService;

    @Mock
    private Tournament tournament;

    @Mock
    private TournamentTable table;

    private TournamentTableGameService service;

    private final UUID tournamentId = UUID.randomUUID();
    private final UUID tableId = UUID.randomUUID();
    private final UUID player1 = UUID.randomUUID();
    private final UUID player2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TournamentTableGameService(tableRepository, registrationRepository, pokerGameService);
    }

    @Test
    @DisplayName("creates new game with tournament blinds and stable player ids")
    @SuppressWarnings("unchecked")
    void createsNewGameForTable() {
        stubPlayableTable(null);
        TournamentRegistration reg1 = mockRegistration(player1, "Alice", 5000);
        TournamentRegistration reg2 = mockRegistration(player2, "Bob", 4800);
        when(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, player1))
                .thenReturn(Optional.of(reg1));
        when(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, player2))
                .thenReturn(Optional.of(reg2));

        Game created = new Game();
        created.setId(UUID.randomUUID());
        when(pokerGameService.createNewGame(any(), anyInt(), anyInt())).thenReturn(created);
        when(tableRepository.save(table)).thenReturn(table);

        Game result = service.getOrStartTableHand(tournamentId, tableId);

        assertThat(result).isSameAs(created);
        ArgumentCaptor<List<PlayerInfo>> playersCaptor = ArgumentCaptor.forClass(List.class);
        verify(pokerGameService).createNewGame(playersCaptor.capture(), eq(25), eq(50));
        assertThat(playersCaptor.getValue()).extracting(PlayerInfo::getPlayerId)
                .containsExactlyInAnyOrder(player1, player2);
        verify(table).startNewGame(created);
        verify(tableRepository).save(table);
    }

    @Test
    @DisplayName("returns active game when hand is in progress")
    void returnsActiveGame() {
        Game active = new Game();
        active.setId(UUID.randomUUID());
        active.setFinished(false);
        stubPlayableTable(active);
        when(pokerGameService.getGame(active.getId())).thenReturn(Optional.of(active));

        Game result = service.getOrStartTableHand(tournamentId, tableId);

        assertThat(result).isSameAs(active);
    }

    private void stubPlayableTable(Game currentGame) {
        lenient().when(tableRepository.findByIdAndTournamentIdWithDetails(tableId, tournamentId))
                .thenReturn(Optional.of(table));
        lenient().when(table.getTournament()).thenReturn(tournament);
        lenient().when(tournament.getId()).thenReturn(tournamentId);
        lenient().when(tournament.getStatus()).thenReturn(TournamentStatus.RUNNING);
        lenient().when(tournament.getCurrentBlindLevel()).thenReturn(new BlindLevel(1, 25, 50, 0));
        lenient().when(table.isActive()).thenReturn(true);
        lenient().when(table.canStartHand()).thenReturn(true);
        lenient().when(table.getCurrentGame()).thenReturn(currentGame);
        lenient().when(table.getPlayerIds()).thenReturn(List.of(player1, player2));
    }

    private static TournamentRegistration mockRegistration(UUID playerId, String name, int chips) {
        TournamentRegistration reg = mock(TournamentRegistration.class);
        lenient().when(reg.getPlayerId()).thenReturn(playerId);
        lenient().when(reg.getPlayerName()).thenReturn(name);
        lenient().when(reg.getCurrentChips()).thenReturn(chips);
        return reg;
    }
}
