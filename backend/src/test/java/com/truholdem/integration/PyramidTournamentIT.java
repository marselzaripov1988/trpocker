package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.RegistrationStatus;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentStatus;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.PyramidTournamentService;
import com.truholdem.service.tournament.PyramidTournamentService.PyramidRunResult;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.game.bot-mode=passive",
        "app.tournament.pyramid-default-hands-per-round=2",
        "app.tournament.pyramid-default-seats-per-table=10"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Pyramid tournament integration")
class PyramidTournamentIT {

    private static final int PLAYER_COUNT = 100;

    @Autowired
    private TournamentService tournamentService;

    @Autowired
    private PyramidTournamentService pyramidTournamentService;

    @Autowired
    private TournamentRepository tournamentRepository;

    @Autowired
    private TournamentRegistrationRepository registrationRepository;

    @Autowired
    private TournamentTableRepository tableRepository;

    @BeforeEach
    void clean() {
        registrationRepository.deleteAll();
        tableRepository.deleteAll();
        tournamentRepository.deleteAll();
    }

    @Test
    @Timeout(600)
    @DisplayName("100 bots: 10 tables → 10 survivors → 1 champion")
    @Transactional
    void shouldRunPyramidFrom100PlayersToOneWinner() {
        Tournament tournament = tournamentService.createTournament(
                CreateTournamentRequest.pyramid(
                        "Pyramid 100 " + System.currentTimeMillis(),
                        PLAYER_COUNT,
                        10,
                        2));

        UUID tournamentId = tournament.getId();

        for (int i = 1; i <= PLAYER_COUNT; i++) {
            tournamentService.registerPlayer(
                    tournamentId,
                    UUID.randomUUID(),
                    "Bot_" + i);
        }

        PyramidRunResult result = pyramidTournamentService.runToCompletion(tournamentId);

        assertThat(result.finalStatus()).isEqualTo(TournamentStatus.COMPLETED);
        assertThat(result.roundsPlayed()).isEqualTo(2);

        long active = registrationRepository.findByTournamentId(tournamentId).stream()
                .filter(r -> r.getStatus() == RegistrationStatus.PLAYING)
                .count();
        assertThat(active).isZero();

        long champions = registrationRepository.findByTournamentId(tournamentId).stream()
                .filter(r -> r.getFinishPosition() != null && r.getFinishPosition() == 1)
                .count();
        assertThat(champions).isOne();
        assertThat(result.championId()).isNotNull();

        long eliminated = registrationRepository.findByTournamentId(tournamentId).stream()
                .filter(r -> r.getStatus() == RegistrationStatus.ELIMINATED)
                .count();
        assertThat(eliminated).isEqualTo(PLAYER_COUNT - 1);

        long totalChips = registrationRepository.sumActiveChipsByTournamentId(tournamentId);
        assertThat(totalChips).isZero();
    }
}
