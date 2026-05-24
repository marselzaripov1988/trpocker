package com.truholdem.service.tournament;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.repository.TournamentRegistrationRepository;

/**
 * Keeps {@link com.truholdem.model.TournamentRegistration} chip counts aligned with
 * finished table hands (Phase 4b).
 */
@Service
public class TournamentChipSyncService {

    private static final Logger log = LoggerFactory.getLogger(TournamentChipSyncService.class);

    private final TournamentRegistrationRepository registrationRepository;

    public TournamentChipSyncService(TournamentRegistrationRepository registrationRepository) {
        this.registrationRepository = registrationRepository;
    }

    /**
     * Copies each seated player's stack from the finished game into their tournament registration.
     */
    @Transactional
    public void syncAfterHand(UUID tournamentId, Game finishedGame) {
        if (finishedGame == null || !finishedGame.isFinished()) {
            return;
        }
        for (Player player : finishedGame.getPlayers()) {
            if (player.getId() == null) {
                continue;
            }
            registrationRepository.findByTournamentIdAndPlayerId(tournamentId, player.getId())
                    .ifPresent(reg -> {
                        int stack = player.getChips();
                        if (reg.getCurrentChips() != stack) {
                            reg.setChips(stack);
                            registrationRepository.save(reg);
                            log.debug("Synced chips for player {} in tournament {}: {}",
                                    player.getId(), tournamentId, stack);
                        }
                    });
        }
    }
}
