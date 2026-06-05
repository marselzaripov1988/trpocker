package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.FederationShardStatus;
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.FederatedPyramidService;

/**
 * Federated pyramid slice 2: register players into a federation (wave fill) and promote filled shards into
 * running child pyramids under the concurrency cap. Uses seats=2 so a tiny 8-player / 4-shard federation is a
 * valid plan (shardCount >= seatsPerTable).
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
@DisplayName("Federated pyramid: registration wave-fill + capped shard promotion")
class FederatedPyramidServiceIT {

    @Autowired private FederatedPyramidService federatedService;
    @Autowired private TournamentService tournamentService;
    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;
    @Autowired private PyramidFederationRegistrationRepository registrationRepository;

    private UUID federationId;

    @BeforeEach
    void setUp() {
        registrationRepository.deleteAll();
        shardRepository.deleteAll();
        federationRepository.deleteAll();
        // 8 players / shardSize 2 → 4 shards of a 2-player pyramid; 4 finalists.
        PyramidFederation fed = federatedService.createFederation(
                "Fed " + System.currentTimeMillis(), 8, 2, null);
        federationId = fed.getId();
    }

    private void register(int n) {
        for (int i = 0; i < n; i++) {
            federatedService.register(federationId, UUID.randomUUID(), "Bot_" + i);
        }
    }

    @Test
    @DisplayName("createFederation lays out 4 shards: shard 0 open, the rest PENDING")
    void layout() {
        var shards = shardRepository.findByFederationIdOrderByShardIndexAsc(federationId);
        assertThat(shards).hasSize(4);
        assertThat(shards.get(0).getStatus()).isEqualTo(FederationShardStatus.REGISTERING);
        assertThat(shards.get(1).getStatus()).isEqualTo(FederationShardStatus.PENDING);
        assertThat(shards.get(0).getNodeGroup()).isEqualTo("ng-0");
    }

    @Test
    @DisplayName("filling assigns players in order and flips each full shard to READY, opening the next")
    void waveFill() {
        register(8);
        var shards = shardRepository.findByFederationIdOrderByShardIndexAsc(federationId);
        assertThat(shards).allMatch(s -> s.getStatus() == FederationShardStatus.READY);
        assertThat(shards).allMatch(s -> s.getFilledCount() == 2);
        assertThat(registrationRepository.countByFederationId(federationId)).isEqualTo(8);
    }

    @Test
    @DisplayName("promoteShards starts shards up to the cap (2); the rest stay READY")
    void cappedPromotion() {
        register(8);

        int started = federatedService.promoteShards(federationId);

        assertThat(started).isEqualTo(2);
        assertThat(shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.RUNNING))
                .isEqualTo(2);
        assertThat(shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.READY))
                .isEqualTo(2);
        assertThat(federationRepository.findById(federationId).orElseThrow().getStatus())
                .isEqualTo(FederationStatus.SHARDS_RUNNING);

        // Each running shard has a child pyramid tournament seeded with its 2 players.
        for (PyramidFederationShard shard :
                shardRepository.findByFederationIdAndStatus(federationId, FederationShardStatus.RUNNING)) {
            assertThat(shard.getTournamentId()).isNotNull();
            assertThat(tournamentService.registeredCount(shard.getTournamentId())).isEqualTo(2);
        }
        // Cap reached → a second promote starts nothing more.
        assertThat(federatedService.promoteShards(federationId)).isZero();
    }

    @Test
    @DisplayName("draining runs every shard to a winner and flips the federation to AWAITING_FINAL")
    void drainToAwaitFinal() {
        register(8);

        FederationStatus status = federatedService.drainShards(federationId);

        assertThat(status).isEqualTo(FederationStatus.AWAITING_FINAL);
        var shards = shardRepository.findByFederationIdOrderByShardIndexAsc(federationId);
        assertThat(shards).allMatch(s -> s.getStatus() == FederationShardStatus.COMPLETED);
        assertThat(shards).allMatch(s -> s.getWinnerPlayerId() != null);
        assertThat(shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.COMPLETED))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("registration is idempotent per player and rejected once every shard is full")
    void idempotentAndFull() {
        UUID player = UUID.randomUUID();
        PyramidFederationShard first = federatedService.register(federationId, player, "Alice");
        PyramidFederationShard again = federatedService.register(federationId, player, "Alice");
        assertThat(again.getShardIndex()).isEqualTo(first.getShardIndex());
        assertThat(registrationRepository.countByFederationId(federationId)).isEqualTo(1);

        // Fill the remaining 7 seats (8 capacity total) → all shards READY.
        register(7);
        assertThatThrownBy(() -> federatedService.register(federationId, UUID.randomUUID(), "Late"))
                .isInstanceOf(IllegalStateException.class);
    }
}
