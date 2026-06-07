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
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.truholdem.TestConstants;
import com.truholdem.TruholdemApplication;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.service.PokerGameService;
import com.truholdem.service.cluster.TableOwnershipService;

/**
 * Phase C capstone: a full multi-street hand on the <b>aggregate engine</b> played <b>across two cluster nodes</b>.
 * The game is created (and owned) on node-A, but every action is submitted to node-B, the non-owner, so each one is
 * forwarded over HTTP to node-A, applied in the aggregate kernel, persisted to the shared Redis hot-state, and
 * reloaded cross-node. Reaching a five-card showdown proves the deck survives cross-node Redis round-trips (the
 * hot-state mapper fix) and that routing + ownership + hot-state compose correctly under {@code engine=aggregate}.
 */
@Testcontainers
@DisplayName("Aggregate engine — multi-street hand routed across two cluster nodes")
class AggregateClusterRoutingIT {

    private static final String SECRET = "cluster-test-secret";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("truholdem_cluster_agg")
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
        nodeA = bootNode("agg-node-A", freePort(), "create");
        nodeB = bootNode("agg-node-B", freePort(), "none");
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
        args.add("--spring.autoconfigure.exclude=");
        args.add("--app.game.engine=aggregate");
        args.add("--app.game.single-writer-enabled=true");
        args.add("--app.cluster.ownership-enabled=true");
        args.add("--app.cluster.routing-enabled=true");
        args.add("--app.cluster.node-base-url=http://localhost:" + port + "/api");
        args.add("--app.cluster.shared-secret=" + SECRET);
        // Don't let timers auto-act / deal the next hand while the test drives a single hand across nodes.
        args.add("--app.game.turn-action-timeout-seconds=600");
        args.add("--app.game.hand-result-delay-seconds=600");
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
    @DisplayName("a check/call hand driven through the non-owner node reaches a five-card showdown")
    void multiStreetHandRoutedToOwner() {
        PokerGameService svcA = nodeA.getBean(PokerGameService.class);
        PokerGameService svcB = nodeB.getBean(PokerGameService.class);
        TableOwnershipService ownA = nodeA.getBean(TableOwnershipService.class);

        // Created (and owned) on node-A.
        Game game = svcA.createNewGame(List.of(
                new PlayerInfo("P1", 1000, false),
                new PlayerInfo("P2", 1000, false)));
        UUID gameId = game.getId();
        assertThat(ownA.isOwner(gameId)).as("node-A owns the freshly created table").isTrue();

        int maxBoard = 0;
        int guard = 0;
        while (!game.isFinished() && game.getPhase() != GamePhase.SHOWDOWN && guard++ < 40) {
            Player current = game.getCurrentPlayer();
            if (current == null) {
                break;
            }
            PlayerAction action = current.getBetAmount() < game.getCurrentBet()
                    ? PlayerAction.CALL
                    : PlayerAction.CHECK;
            // Submit to node-B (the non-owner): it forwards to node-A, which applies the action in the aggregate
            // kernel and persists to shared Redis; node-B then reloads the authoritative state cross-node.
            game = svcB.playerAct(gameId, UUID.randomUUID(), current.getId(), action, 0);
            maxBoard = Math.max(maxBoard, game.getCommunityCards().size());
        }

        Game finished = svcB.getGame(gameId).orElseThrow();
        maxBoard = Math.max(maxBoard, finished.getCommunityCards().size());

        assertThat(finished.isFinished()).as("the routed hand reached showdown").isTrue();
        assertThat(maxBoard).as("turn and river were dealt — the deck survived cross-node Redis round-trips")
                .isEqualTo(5);
        assertThat(finished.getWinnerName()).as("a winner was determined").isNotNull();
        assertThat(finished.getPlayers().stream().mapToInt(Player::getChips).sum())
                .as("chips conserved across the routed multi-street hand").isEqualTo(2000);
        assertThat(ownA.isOwner(gameId)).as("ownership stayed on node-A throughout").isTrue();
    }
}
