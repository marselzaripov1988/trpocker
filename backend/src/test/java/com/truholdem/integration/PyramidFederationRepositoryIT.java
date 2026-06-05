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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.FederationShardStatus;
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;

@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Federated pyramid persistence: federation + shards, unique shard index, finders")
class PyramidFederationRepositoryIT {

    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;

    private UUID federationId;

    @BeforeEach
    void setUp() {
        shardRepository.deleteAll();
        federationRepository.deleteAll();
        PyramidFederation fed = federationRepository.save(
                new PyramidFederation("Federated " + System.currentTimeMillis(), 10_000, 100, 10, 3, null));
        federationId = fed.getId();
    }

    @Test
    @DisplayName("federation persists with REGISTERING status and an indefinite (null) deadline")
    void federationDefaults() {
        PyramidFederation fed = federationRepository.findById(federationId).orElseThrow();
        assertThat(fed.getStatus()).isEqualTo(FederationStatus.REGISTERING);
        assertThat(fed.isRegistrationIndefinite()).isTrue();
        assertThat(fed.getShardCount()).isEqualTo(100);
        assertThat(fed.getVersion()).isNotNull();
    }

    @Test
    @DisplayName("shards persist, order by index, and status counts work")
    void shardsAndCounts() {
        for (int i = 0; i < 5; i++) {
            shardRepository.save(new PyramidFederationShard(federationId, i, "node-group-" + (i % 2)));
        }
        // Advance two shards to RUNNING.
        var shards = shardRepository.findByFederationIdOrderByShardIndexAsc(federationId);
        assertThat(shards).hasSize(5);
        assertThat(shards.get(0).getShardIndex()).isEqualTo(0);
        shards.get(0).markRunning(UUID.randomUUID());
        shards.get(1).markRunning(UUID.randomUUID());
        shardRepository.saveAll(shards);

        assertThat(shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.RUNNING))
                .isEqualTo(2);
        assertThat(shardRepository.countByFederationIdAndStatus(federationId, FederationShardStatus.PENDING))
                .isEqualTo(3);
        assertThat(shardRepository.findByFederationIdAndShardIndex(federationId, 3)).isPresent();
    }

    @Test
    @DisplayName("a shard winner is found by its child tournament id")
    void findByTournamentId() {
        UUID childTournament = UUID.randomUUID();
        PyramidFederationShard shard = new PyramidFederationShard(federationId, 0, null);
        shard.markRunning(childTournament);
        shard.completeWith(UUID.randomUUID());
        shardRepository.save(shard);

        PyramidFederationShard found = shardRepository.findByTournamentId(childTournament).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(FederationShardStatus.COMPLETED);
        assertThat(found.getWinnerPlayerId()).isNotNull();
    }

    @Test
    @DisplayName("two shards cannot share an index within a federation")
    void uniqueShardIndex() {
        shardRepository.save(new PyramidFederationShard(federationId, 7, null));
        assertThatThrownBy(() -> {
            shardRepository.save(new PyramidFederationShard(federationId, 7, null));
            shardRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
