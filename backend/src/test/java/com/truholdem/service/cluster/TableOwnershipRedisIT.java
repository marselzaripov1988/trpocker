package com.truholdem.service.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.truholdem.TestConstants;
import com.truholdem.config.AppProperties;

/**
 * Phase 5 verification against a real Redis: two {@link TableOwnershipService} instances (two "nodes")
 * contend for the same table over one Redis container. Proves the lease semantics the unit tests can
 * only mock — NX exclusivity, isOwner, release handoff, and TTL-expiry failover.
 */
@Testcontainers
@DisplayName("TableOwnershipService — real Redis lease (Testcontainers)")
class TableOwnershipRedisIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(TestConstants.REDIS_IMAGE))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startRedis() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /** A node-scoped ownership service against the shared Redis container. */
    private TableOwnershipService node(String instanceId, long leaseTtlMillis) {
        AppProperties props = new AppProperties();
        props.getCluster().setOwnershipEnabled(true);
        props.getCluster().setLeaseTtlMillis(leaseTtlMillis);
        return new TableOwnershipService(props, fixedProvider(redisTemplate), instanceId);
    }

    private static ObjectProvider<StringRedisTemplate> fixedProvider(StringRedisTemplate template) {
        return new ObjectProvider<>() {
            @Override
            public StringRedisTemplate getObject() {
                return template;
            }

            @Override
            public StringRedisTemplate getObject(Object... args) {
                return template;
            }

            @Override
            public StringRedisTemplate getIfAvailable() {
                return template;
            }

            @Override
            public StringRedisTemplate getIfUnique() {
                return template;
            }
        };
    }

    @Test
    @DisplayName("only one node can own a table; the other is rejected")
    void exclusiveOwnership() {
        TableOwnershipService nodeA = node("node-A", 30_000);
        TableOwnershipService nodeB = node("node-B", 30_000);
        UUID table = UUID.randomUUID();

        assertThat(nodeA.acquire(table)).isTrue();
        assertThat(nodeB.acquire(table)).isFalse();

        assertThat(nodeA.isOwner(table)).isTrue();
        assertThat(nodeB.isOwner(table)).isFalse();
    }

    @Test
    @DisplayName("after the owner releases, another node can acquire")
    void releaseHandsOver() {
        TableOwnershipService nodeA = node("node-A", 30_000);
        TableOwnershipService nodeB = node("node-B", 30_000);
        UUID table = UUID.randomUUID();

        assertThat(nodeA.acquire(table)).isTrue();
        nodeA.release(table);

        assertThat(nodeB.acquire(table)).isTrue();
        assertThat(nodeB.isOwner(table)).isTrue();
    }

    @Test
    @DisplayName("failover: when the owner stops renewing, the lease expires and another node takes over")
    void leaseExpiryFailover() throws InterruptedException {
        TableOwnershipService dyingOwner = node("node-A", 1_000); // 1s lease
        TableOwnershipService survivor = node("node-B", 30_000);
        UUID table = UUID.randomUUID();

        assertThat(dyingOwner.acquire(table)).isTrue();
        assertThat(survivor.acquire(table)).isFalse();

        // Owner "dies": stops renewing. Lease (1s) expires.
        Thread.sleep(1_300);

        assertThat(survivor.acquire(table)).isTrue();
        assertThat(survivor.isOwner(table)).isTrue();
    }
}
