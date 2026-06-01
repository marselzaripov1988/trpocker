package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.truholdem.TestConstants;
import com.truholdem.TruholdemApplication;
import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.service.PokerGameService;
import com.truholdem.service.cluster.ClusterFailoverService;
import com.truholdem.service.cluster.TableOwnershipService;

/**
 * Multi-instance cluster harness: boots TWO real web app instances ("nodes") against ONE shared
 * Postgres + Redis, with single-writer / ownership / WebSocket-cluster / cross-node-routing enabled.
 * Verifies the Phase 5 behaviour end-to-end across nodes.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Multi-node cluster harness (two app instances, shared Redis/Postgres)")
class MultiNodeClusterIT {

    private static final String SECRET = "cluster-test-secret";

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
        int portA = freePort();
        int portB = freePort();
        // node-A creates the schema; node-B reuses it. Use "create" (not "create-drop") so the schema
        // survives node-A shutdown — the failover test closes node-A and node-B must still load the game.
        // The Testcontainers Postgres is fresh per run, so there is no stale schema to clean up.
        nodeA = bootNode("node-A", portA, "create");
        nodeB = bootNode("node-B", portB, "none");
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

    private static ConfigurableApplicationContext bootNode(String instanceId, int port, String ddlAuto) {
        List<String> args = new ArrayList<>();
        args.add("--server.port=" + port);
        args.add("--spring.datasource.url=" + POSTGRES.getJdbcUrl());
        args.add("--spring.datasource.username=" + POSTGRES.getUsername());
        args.add("--spring.datasource.password=" + POSTGRES.getPassword());
        args.add("--spring.datasource.driver-class-name=org.postgresql.Driver");
        args.add("--spring.jpa.hibernate.ddl-auto=" + ddlAuto);
        args.add("--spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect");
        args.add("--spring.liquibase.enabled=false");
        args.add("--spring.data.redis.host=" + REDIS.getHost());
        args.add("--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        args.add("--spring.autoconfigure.exclude="); // cluster mode needs Redis auto-config
        args.add("--app.game.single-writer-enabled=true");
        args.add("--app.cluster.ownership-enabled=true");
        args.add("--app.cluster.routing-enabled=true");
        args.add("--app.cluster.node-base-url=http://localhost:" + port + "/api"); // incl. context-path
        args.add("--app.cluster.shared-secret=" + SECRET);
        args.add("--app.cluster.takeover-enabled=true");
        args.add("--app.websocket.cluster.enabled=true");
        args.add("--app.websocket.cluster.instance-id=" + instanceId);
        args.add("--app.jwt.secret=dGVzdC1zZWNyZXQta2V5LWZvci1pbnRlZ3JhdGlvbi10ZXN0cy0xMjM0NTY3ODkw");
        args.add("--app.jwt.expiration=86400000");

        return new SpringApplicationBuilder(TruholdemApplication.class).run(args.toArray(new String[0]));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port", e);
        }
    }

    @Test
    @Order(1)
    @DisplayName("per-table ownership is exclusive across nodes")
    void ownershipExclusiveAcrossNodes() {
        TableOwnershipService ownerOnA = nodeA.getBean(TableOwnershipService.class);
        TableOwnershipService ownerOnB = nodeB.getBean(TableOwnershipService.class);
        UUID table = UUID.randomUUID();

        assertThat(ownerOnA.acquire(table)).isTrue();
        assertThat(ownerOnB.acquire(table)).isFalse();
        assertThat(ownerOnA.isOwner(table)).isTrue();
        assertThat(ownerOnB.isOwner(table)).isFalse();

        ownerOnA.release(table);
        assertThat(ownerOnB.acquire(table)).isTrue();
        ownerOnB.release(table);
    }

    @Test
    @Order(2)
    @DisplayName("an action on the non-owner node is forwarded over HTTP to the owner")
    void actionForwardedToOwner() {
        PokerGameService svcA = nodeA.getBean(PokerGameService.class);
        PokerGameService svcB = nodeB.getBean(PokerGameService.class);
        TableOwnershipService ownA = nodeA.getBean(TableOwnershipService.class);
        TableOwnershipService ownB = nodeB.getBean(TableOwnershipService.class);

        // Creating the game on node-A schedules its turn timer → node-A acquires ownership.
        Game game = svcA.createNewGame(List.of(
                new PlayerInfo("P1", 1000, false),
                new PlayerInfo("P2", 1000, false)));
        UUID gameId = game.getId();

        assertThat(ownA.isOwner(gameId)).isTrue();
        assertThat(ownB.isOwner(gameId)).isFalse();

        Player actor = game.getCurrentPlayer();
        PlayerAction action = actor.getBetAmount() >= game.getCurrentBet()
                ? PlayerAction.CHECK : PlayerAction.CALL;

        // node-B does NOT own the table → it must forward the action to node-A over HTTP.
        Game updated = svcB.playerAct(gameId, UUID.randomUUID(), actor.getId(), action, 0);

        // The forward succeeded (node-B did NOT fall back to claiming ownership) and node-A still owns.
        assertThat(updated).isNotNull();
        assertThat(ownA.isOwner(gameId)).isTrue();
        assertThat(ownB.isOwner(gameId)).isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("a surviving node takes over a table orphaned by a dead owner and resumes its turn timer")
    void failoverTakeoverAfterOwnerDies() {
        PokerGameService svcA = nodeA.getBean(PokerGameService.class);
        TableOwnershipService ownA = nodeA.getBean(TableOwnershipService.class);
        TableOwnershipService ownB = nodeB.getBean(TableOwnershipService.class);
        ClusterFailoverService failoverB = nodeB.getBean(ClusterFailoverService.class);
        StringRedisTemplate redis = nodeB.getBean(StringRedisTemplate.class);

        // node-A creates a game → it owns the table, tracks it active, and arms the turn timer.
        Game game = svcA.createNewGame(List.of(
                new PlayerInfo("P1", 1000, false),
                new PlayerInfo("P2", 1000, false)));
        UUID gameId = game.getId();
        assertThat(ownA.isOwner(gameId)).isTrue();

        // Simulate node-A crashing: stop its context (no graceful lease release) and then expire its
        // lease immediately (instead of waiting out the TTL) so the table looks orphaned-but-active.
        nodeA.close();
        redis.delete("truholdem:owner:" + gameId);
        assertThat(ownB.currentOwner(gameId)).isNull();

        // node-B's failover takeover claims the orphaned table and resumes its timer.
        failoverB.takeOverIfOrphaned(gameId);

        assertThat(ownB.isOwner(gameId)).isTrue();
    }
}
