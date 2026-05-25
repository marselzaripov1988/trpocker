package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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

/**
 * Heavy load test: 10_000 → 1_000 → 100 → 10 → 1. Enable with {@code -Dpyramid10k=true}.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "pyramid10k", matches = "true")
@TestPropertySource(properties = {
        "app.game.bot-mode=passive",
        "app.game.persist-on-hand-end-only=true",
        "app.game.hot-state-enabled=false",
        "app.tournament.pyramid-default-hands-per-round=1",
        "app.tournament.pyramid-default-seats-per-table=10",
        "app.tournament.pyramid-table-parallelism=16",
        "logging.level.com.truholdem=INFO"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Pyramid tournament 10k load test")
class PyramidTournament10kIT {

    private static final int PLAYER_COUNT = 10_000;

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
    @Timeout(7200)
    @DisplayName("10k bots: 1000 tables → … → 1 champion")
    void shouldRunPyramidFrom10kPlayersToOneWinner() {
        long t0 = System.currentTimeMillis();

        Tournament tournament = tournamentService.createTournament(
                CreateTournamentRequest.pyramid(
                        "Pyramid 10k " + System.currentTimeMillis(),
                        PLAYER_COUNT,
                        10,
                        1));
        var tournamentId = tournament.getId();

        long tReg = System.currentTimeMillis();
        tournamentService.registerBotPlayersBatch(tournamentId, PLAYER_COUNT, "Bot_");
        System.out.printf("Registered %d bots in %d ms%n", PLAYER_COUNT, System.currentTimeMillis() - tReg);

        PyramidRunResult result = pyramidTournamentService.runToCompletion(tournamentId);

        System.out.printf("Pyramid 10k finished in %d ms, rounds=%d, champion=%s%n",
                System.currentTimeMillis() - t0, result.roundsPlayed(), result.championId());

        assertThat(result.finalStatus()).isEqualTo(TournamentStatus.COMPLETED);
        assertThat(result.roundsPlayed()).isEqualTo(4);

        assertThat(registrationRepository.findByTournamentId(tournamentId).stream()
                .filter(r -> r.getFinishPosition() != null && r.getFinishPosition() == 1)
                .count()).isOne();

        assertThat(registrationRepository.findByTournamentId(tournamentId).stream()
                .filter(r -> r.getStatus() == RegistrationStatus.ELIMINATED)
                .count()).isEqualTo(PLAYER_COUNT - 1);

        assertThat(registrationRepository.findByTournamentId(tournamentId).stream()
                .filter(r -> r.getStatus() == RegistrationStatus.PLAYING)
                .count()).isZero();
    }
}
