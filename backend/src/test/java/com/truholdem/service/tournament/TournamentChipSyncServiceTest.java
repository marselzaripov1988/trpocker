package com.truholdem.service.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.repository.TournamentRegistrationRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentChipSyncService Tests (Phase 4b)")
class TournamentChipSyncServiceTest {

    @Mock
    private TournamentRegistrationRepository registrationRepository;

    private TournamentChipSyncService chipSyncService;

    private final UUID tournamentId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        chipSyncService = new TournamentChipSyncService(registrationRepository);
    }

    @Test
    @DisplayName("syncAfterHand copies finished game stacks into registrations")
    void syncAfterHand_updatesRegistrationChips() {
        TournamentRegistration registration = org.mockito.Mockito.mock(TournamentRegistration.class);
        when(registration.getCurrentChips()).thenReturn(10_000);
        when(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, playerId))
                .thenReturn(Optional.of(registration));

        Game game = finishedGame(playerId, 8_750);

        chipSyncService.syncAfterHand(tournamentId, game);

        verify(registration).setChips(8_750);
        verify(registrationRepository).save(registration);
    }

    @Test
    @DisplayName("syncAfterHand skips in-progress hands")
    void syncAfterHand_skipsWhenHandNotFinished() {
        Game game = finishedGame(playerId, 8_750);
        game.setFinished(false);

        chipSyncService.syncAfterHand(tournamentId, game);

        verify(registrationRepository, never()).findByTournamentIdAndPlayerId(any(), any());
        verify(registrationRepository, never()).save(any());
    }

    @Test
    @DisplayName("syncAfterHand does not write when stacks already match")
    void syncAfterHand_skipsWhenChipsUnchanged() {
        TournamentRegistration registration = org.mockito.Mockito.mock(TournamentRegistration.class);
        when(registration.getCurrentChips()).thenReturn(8_750);
        when(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, playerId))
                .thenReturn(Optional.of(registration));

        chipSyncService.syncAfterHand(tournamentId, finishedGame(playerId, 8_750));

        verify(registration, never()).setChips(org.mockito.ArgumentMatchers.anyInt());
        verify(registrationRepository, never()).save(any());
    }

    private static Game finishedGame(UUID playerId, int chips) {
        Game game = new Game();
        game.setId(UUID.randomUUID());
        game.setFinished(true);
        Player player = new Player();
        player.setId(playerId);
        player.setName("Alice");
        player.setChips(chips);
        game.getPlayers().add(player);
        return game;
    }
}
