package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationRegistration;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.model.User;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.repository.UserRepository;
import com.truholdem.service.EmailService;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.FederatedPyramidService;

/**
 * Federated pyramid slice 4: admin schedules the final (e-mailing the finalists) and the final pyramid is
 * created + seeded from the shard winners. seats=2 keeps the seeding flow a tiny valid federation.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.game.bot-mode=passive",
        "app.tournament.pyramid-default-seats-per-table=2",
        "app.tournament.pyramid-default-hands-per-round=1",
        "app.tournament.federated-max-concurrent-shards=2"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Federated pyramid: schedule final + notify finalists + seed final pyramid")
class FederatedPyramidFinalIT {

    @Autowired private FederatedPyramidService federatedService;
    @Autowired private TournamentService tournamentService;
    @Autowired private UserRepository userRepository;
    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;
    @Autowired private PyramidFederationRegistrationRepository registrationRepository;

    @MockitoBean private EmailService emailService;

    @BeforeEach
    void setUp() {
        registrationRepository.deleteAll();
        shardRepository.deleteAll();
        federationRepository.deleteAll();
    }

    @Test
    @DisplayName("scheduleFinal e-mails each resolvable finalist and moves to FINAL_SCHEDULED")
    void scheduleFinalNotifiesFinalists() {
        // Build an AWAITING_FINAL federation with 3 completed shards whose winners are real users.
        PyramidFederation fed = federationRepository.save(
                new PyramidFederation("Fed " + System.currentTimeMillis(), 2, 3, 2, 1, null));
        fed.markAwaitingFinal();
        federationRepository.save(fed);

        User[] winners = new User[3];
        for (int i = 0; i < 3; i++) {
            String u = "fin-" + i + "-" + UUID.randomUUID();
            winners[i] = userRepository.save(new User(u, u + "@example.com", "x"));
            PyramidFederationShard shard = new PyramidFederationShard(fed.getId(), i, null);
            shard.markRunning(UUID.randomUUID());
            shard.completeWith(winners[i].getId());
            shardRepository.save(shard);
            registrationRepository.save(new PyramidFederationRegistration(
                    fed.getId(), i, winners[i].getId(), winners[i].getUsername()));
        }

        Instant when = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        int notified = federatedService.scheduleFinal(fed.getId(), when);

        assertThat(notified).isEqualTo(3);
        PyramidFederation reloaded = federationRepository.findById(fed.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(FederationStatus.FINAL_SCHEDULED);
        assertThat(reloaded.getFinalScheduledStart()).isEqualTo(when);
        for (User w : winners) {
            verify(emailService).sendFederationFinalScheduledEmail(
                    eq(w.getEmail()), eq(w.getUsername()), any(), any());
        }
        verify(emailService, times(3)).sendFederationFinalScheduledEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("startFinal creates + seeds the final pyramid from the shard winners")
    void startFinalSeedsWinners() {
        PyramidFederation fed = federatedService.createFederation("Fed " + System.currentTimeMillis(), 8, 2, null);
        for (int i = 0; i < 8; i++) {
            federatedService.register(fed.getId(), UUID.randomUUID(), "Bot_" + i);
        }
        assertThat(federatedService.drainShards(fed.getId())).isEqualTo(FederationStatus.AWAITING_FINAL);

        federatedService.scheduleFinal(fed.getId(), Instant.now().plus(2, ChronoUnit.HOURS));
        PyramidFederation running = federatedService.startFinal(fed.getId());

        assertThat(running.getStatus()).isEqualTo(FederationStatus.FINAL_RUNNING);
        assertThat(running.getFinalTournamentId()).isNotNull();
        // 4 shard winners seeded into the final pyramid.
        assertThat(tournamentService.registeredCount(running.getFinalTournamentId())).isEqualTo(4);
    }

    @Test
    @DisplayName("full lifecycle: fill → shards → final → one grand champion (COMPLETED)")
    void runsToGrandChampion() {
        PyramidFederation fed = federatedService.createFederation("Fed " + System.currentTimeMillis(), 8, 2, null);
        for (int i = 0; i < 8; i++) {
            federatedService.register(fed.getId(), UUID.randomUUID(), "Bot_" + i);
        }
        federatedService.drainShards(fed.getId());
        var winners = shardRepository.findByFederationIdOrderByShardIndexAsc(fed.getId()).stream()
                .map(PyramidFederationShard::getWinnerPlayerId).toList();
        federatedService.scheduleFinal(fed.getId(), Instant.now().plus(2, ChronoUnit.HOURS));
        federatedService.startFinal(fed.getId());

        var result = federatedService.runFinalToChampion(fed.getId());

        assertThat(result.championId()).isNotNull();
        PyramidFederation done = federationRepository.findById(fed.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(FederationStatus.COMPLETED);
        assertThat(done.getChampionPlayerId()).isEqualTo(result.championId());
        // The grand champion is one of the four shard winners.
        assertThat(winners).contains(done.getChampionPlayerId());
    }

    @Test
    @DisplayName("scheduleFinal is rejected before all shards are done, and for a past time")
    void scheduleGuards() {
        PyramidFederation fed = federatedService.createFederation("Fed " + System.currentTimeMillis(), 8, 2, null);
        // Still REGISTERING (no shards done) → cannot schedule.
        assertThatThrownBy(() -> federatedService.scheduleFinal(fed.getId(), Instant.now().plus(1, ChronoUnit.HOURS)))
                .isInstanceOf(IllegalStateException.class);

        // Force AWAITING_FINAL, then a past time is rejected.
        PyramidFederation loaded = federationRepository.findById(fed.getId()).orElseThrow();
        loaded.markAwaitingFinal();
        federationRepository.save(loaded);
        assertThatThrownBy(() -> federatedService.scheduleFinal(fed.getId(), Instant.now().minus(1, ChronoUnit.HOURS)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
