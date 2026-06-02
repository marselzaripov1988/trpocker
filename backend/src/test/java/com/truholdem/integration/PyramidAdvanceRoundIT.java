package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import com.truholdem.model.TournamentRegistration;
import com.truholdem.model.TournamentTable;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.TournamentStartService;
import com.truholdem.service.tournament.PyramidTournamentService;

/**
 * Focuses on the pyramid "advance to the next-level table" step (переход на стол следующего уровня):
 * after one round, the chip leader of each round-1 table survives and all survivors are re-seated onto the
 * next pyramid level. With 30 players, 10 seats/table, 1 hand/round → 3 round-1 tables → 3 survivors → a
 * single final table at round 2. Complements {@code PyramidTournamentIT} (which only asserts the end state).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.game.bot-mode=passive",
        "app.tournament.pyramid-default-hands-per-round=1",
        "app.tournament.pyramid-default-seats-per-table=10"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Pyramid tournament — advance to next-level table")
class PyramidAdvanceRoundIT {

    private static final int PLAYER_COUNT = 30;
    private static final int SEATS_PER_TABLE = 10;

    @Autowired
    private TournamentService tournamentService;
    @Autowired
    private TournamentStartService tournamentStartService;
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
    @Timeout(300)
    @DisplayName("one round: each table's chip leader survives and is re-seated on the next level")
    void shouldPromoteSurvivorsToNextLevelTable() {
        Tournament tournament = tournamentService.createTournament(
                CreateTournamentRequest.pyramid(
                        "Pyramid advance " + System.currentTimeMillis(),
                        PLAYER_COUNT,
                        SEATS_PER_TABLE,
                        1));
        UUID tournamentId = tournament.getId();

        for (int i = 1; i <= PLAYER_COUNT; i++) {
            tournamentService.registerPlayer(tournamentId, UUID.randomUUID(), "Bot_" + i);
        }

        // Start round 1 (synchronous or async depending on size) → round-1 tables are seeded.
        int registered = registrationRepository.countByTournamentId(tournamentId);
        if (tournamentStartService.shouldStartAsynchronously(registered)) {
            tournamentStartService.completeStart(tournamentId);
        } else {
            tournamentService.startTournament(tournamentId);
        }

        List<TournamentTable> round1 = tableRepository.findActiveTablesByTournament(tournamentId);
        assertThat(round1).as("round-1 tables = ceil(30/10)").hasSize(3);
        assertThat(round1.stream().mapToInt(t -> t.getPlayerIds().size()).sum())
                .as("all 30 players seated in round 1").isEqualTo(PLAYER_COUNT);
        assertThat(tournamentRepository.findById(tournamentId).orElseThrow().getPyramidRound()).isEqualTo(1);

        // --- the step under test: play the round, which resolves each table and advances survivors ---
        pyramidTournamentService.playCurrentPyramidRound(tournamentId);

        Tournament after = tournamentRepository.findById(tournamentId).orElseThrow();
        assertThat(after.getPyramidRound()).as("advanced to level 2").isEqualTo(2);

        List<TournamentRegistration> regs = registrationRepository.findByTournamentId(tournamentId);
        Set<UUID> survivors = regs.stream()
                .filter(r -> r.getStatus() == RegistrationStatus.PLAYING)
                .map(TournamentRegistration::getPlayerId)
                .collect(Collectors.toSet());
        assertThat(survivors).as("one chip-leader survivor per round-1 table").hasSize(3);
        assertThat(regs.stream().filter(r -> r.getStatus() == RegistrationStatus.ELIMINATED).count())
                .as("everyone else eliminated").isEqualTo(PLAYER_COUNT - 3);

        // Old round-1 tables closed; a single next-level (final) table now holds exactly the survivors.
        List<TournamentTable> round2 = tableRepository.findActiveTablesByTournament(tournamentId);
        assertThat(round2).as("3 survivors ≤ 10 seats → one final table").hasSize(1);
        assertThat(round2.get(0).getPlayerIds())
                .as("survivors re-seated exactly once on the next-level table")
                .containsExactlyInAnyOrderElementsOf(survivors);
    }
}
