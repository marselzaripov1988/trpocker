package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.truholdem.TestConstants;
import com.truholdem.TruholdemApplication;
import com.truholdem.service.cluster.TableOwnershipService;

/**
 * Multi-instance cluster harness: boots TWO real application instances ("nodes") against ONE shared
 * Postgres + Redis, with single-writer / ownership / WebSocket-cluster enabled. This is the foundation
 * for verifying the remaining Phase 5 work (cross-node command routing, failover takeover).
 *
 * <p>Baseline established here: per-table ownership is exclusive across nodes (only one node owns a
 * given table at a time over the shared Redis lease). NOTE — same-table player <em>actions</em> are
 * NOT yet coordinated across nodes (per-node single-writer + sticky sessions only); that gap is what
 * cross-node routing / table-affinity will close, and this harness is where it will be verified.
 */
@Testcontainers
@DisplayName("Multi-node cluster harness (two app instances, shared Redis/Postgres)")
class MultiNodeClusterIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("truholdem_cluster")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(TestConstants.REDIS_IMAGE)).withExposedPorts(6379);

    private static ConfigurableApplicationContext nodeA;
    private static ConfigurableApplicationContext nodeB;

    @BeforeAll
    static void startNodes() {
        // node-A creates the schema; node-B reuses it (no second create-drop that would wipe node-A).
        nodeA = bootNode("node-A", "create-drop");
        nodeB = bootNode("node-B", "none");
    }

    @AfterAll
    static void stopNodes() {
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    private static ConfigurableApplicationContext bootNode(String instanceId, String ddlAuto) {
        // Pass as command-line args (highest precedence) so they override application.properties —
        // .properties() would register them as low-precedence defaults instead.
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        args.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
        args.add("--spring.datasource.username=" + POSTGRES.getUsername());
        args.add("--spring.datasource.password=" + POSTGRES.getPassword());
        args.add("--spring.datasource.driver-class-name=org.postgresql.Driver");
        args.add("--spring.jpa.hibernate.ddl-auto=" + ddlAuto);
        args.add("--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect");
        args.add("--spring.liquibase.enabled=false");
        args.add("--spring.data.redis.host=" + REDIS.getHost());
        args.add("--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        // Cluster mode needs a RedisConnectionFactory — make sure no profile excludes Redis auto-config.
        args.add("--spring.autoconfigure.exclude=");
        args.add("--app.game.single-writer-enabled=true");
        args.add("--app.cluster.ownership-enabled=true");
        args.add("--app.websocket.cluster.enabled=true");
        args.add("--app.websocket.cluster.instance-id=" + instanceId);
        args.add("--app.jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci1pbnRlZ3JhdGlvbi10ZXN0cy0xMjM0NTY3ODkw");
        args.add("--app.jwt.expiration=86400000");

        return new SpringApplicationBuilder(TruholdemApplication.class)
                .web(WebApplicationType.NONE)
                .run(args.toArray(new String[0]));
    }

    @Test
    @DisplayName("two nodes boot on shared infra; per-table ownership is exclusive across nodes")
    void ownershipExclusiveAcrossNodes() {
        TableOwnershipService ownerOnA = nodeA.getBean(TableOwnershipService.class);
        TableOwnershipService ownerOnB = nodeB.getBean(TableOwnershipService.class);
        UUID table = UUID.randomUUID();

        assertThat(ownerOnA.acquire(table)).isTrue();   // node-A claims the table
        assertThat(ownerOnB.acquire(table)).isFalse();  // node-B is rejected over shared Redis
        assertThat(ownerOnA.isOwner(table)).isTrue();
        assertThat(ownerOnB.isOwner(table)).isFalse();

        // After node-A releases, node-B can take over (the failover handoff path).
        ownerOnA.release(table);
        assertThat(ownerOnB.acquire(table)).isTrue();
        assertThat(ownerOnB.isOwner(table)).isTrue();
    }
}
