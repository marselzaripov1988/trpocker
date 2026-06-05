package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.service.tournament.FederatedPyramidService;

/**
 * Physical sharding: shards are round-robin pinned across {@code federated-node-group-count} node-groups so a
 * federation's load spreads evenly across the cluster. (Engine-level table affinity to a node-group is a
 * documented follow-up; today each shard is an independent tournament that the lease-based cluster already
 * distributes — the node-group is the recorded placement + an LB/ops hint.)
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.tournament.pyramid-default-seats-per-table=10",
        "app.tournament.federated-node-group-count=3"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Federated pyramid: shards balanced across node-groups (round-robin)")
class FederatedPyramidNodeGroupIT {

    @Autowired private FederatedPyramidService federatedService;
    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;

    @BeforeEach
    void setUp() {
        shardRepository.deleteAll();
        federationRepository.deleteAll();
    }

    @Test
    @DisplayName("12 shards over 3 node-groups → 4 each (ng-0 / ng-1 / ng-2)")
    void balancedAcrossNodeGroups() {
        // 1,200 players / shardSize 100 → 12 shards; seats=10 keeps the plan valid (shardCount >= seats).
        PyramidFederation fed = federatedService.createFederation(
                "NG " + System.currentTimeMillis(), 1_200, 100, null);
        assertThat(fed.getShardCount()).isEqualTo(12);

        Map<String, Long> byGroup = shardRepository.findByFederationIdOrderByShardIndexAsc(fed.getId()).stream()
                .collect(Collectors.groupingBy(PyramidFederationShard::getNodeGroup, Collectors.counting()));

        assertThat(byGroup).containsOnlyKeys("ng-0", "ng-1", "ng-2");
        assertThat(byGroup.values()).allMatch(c -> c == 4L);
    }

    @Test
    @DisplayName("shard 0's node-group is ng-0 and assignment cycles by index")
    void cyclesByIndex() {
        PyramidFederation fed = federatedService.createFederation(
                "NG " + System.currentTimeMillis(), 1_200, 100, null);
        var shards = shardRepository.findByFederationIdOrderByShardIndexAsc(fed.getId());
        for (PyramidFederationShard shard : shards) {
            assertThat(shard.getNodeGroup()).isEqualTo("ng-" + (shard.getShardIndex() % 3));
        }
        UUID anyId = shards.get(0).getId();
        assertThat(anyId).isNotNull();
    }
}
