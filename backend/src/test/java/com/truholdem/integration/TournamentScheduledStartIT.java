package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.Tournament;
import com.truholdem.service.TournamentService;

@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Tournament scheduled-start: persisted column + due query")
class TournamentScheduledStartIT {

    @Autowired
    private TournamentService tournamentService;

    @Test
    @DisplayName("dueForScheduledStart returns only REGISTERING tournaments whose scheduled time has passed")
    void dueQuery() {
        Tournament due = tournamentService.createTournament(
                CreateTournamentRequest.freezeout("Sched due", 0, 9));
        Tournament future = tournamentService.createTournament(
                CreateTournamentRequest.freezeout("Sched future", 0, 9));
        Tournament manual = tournamentService.createTournament(
                CreateTournamentRequest.freezeout("Manual", 0, 9));

        tournamentService.scheduleStart(due.getId(), Instant.now().minusSeconds(60));
        tournamentService.scheduleStart(future.getId(), Instant.now().plusSeconds(3600));
        // `manual` keeps a null scheduledStart → never due.

        var ids = tournamentService.dueForScheduledStart(Instant.now());

        assertThat(ids).contains(due.getId());
        assertThat(ids).doesNotContain(future.getId());
        assertThat(ids).doesNotContain(manual.getId());

        // The scheduled time round-trips on the entity.
        assertThat(tournamentService.getTournament(due.getId()).getScheduledStart()).isNotNull();
        assertThat(tournamentService.getTournament(manual.getId()).getScheduledStart()).isNull();
    }
}
