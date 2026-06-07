package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.truholdem.TestConstants;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.service.GameHandLifecycleService;
import com.truholdem.service.GameTurnTimeoutService;
import com.truholdem.service.PokerGameService;

/**
 * Phase C — exercises the aggregate engine against a <b>real Redis hot-state</b> store. With
 * {@code app.game.hot-state-enabled=true} every action loads the game from Redis, mutates it, and writes it back,
 * so the full state must survive the JSON round-trip. The decisive check is that the board reaches five community
 * cards: dealing the turn and the river requires the <b>deck</b>, which {@code Game.deck} is {@code @JsonIgnore}d
 * from the REST mapper and would be lost in Redis without the dedicated hot-state {@code ObjectMapper}. Before that
 * fix, advancing past the flop threw on an empty deck; here the hand must run to a five-card showdown with chips
 * conserved.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Aggregate engine — multi-street hand over real Redis hot-state")
class AggregateHotStateMultiStreetIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("truholdem_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse(TestConstants.REDIS_IMAGE))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.liquibase.enabled", () -> "false");
        registry.add("spring.cache.type", () -> "simple");
        // Real Redis so hot-state actually serializes/deserializes the game between actions.
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.jwt.secret", () -> "dGVzdC1zZWNyZXQta2V5LWZvci1pbnRlZ3JhdGlvbi10ZXN0cy0xMjM0NTY3ODkw");
        registry.add("app.jwt.expiration", () -> "86400000");
        registry.add("app.game.engine", () -> "aggregate");
        registry.add("app.game.hot-state-enabled", () -> "true");
        // Keep the lifecycle from dealing a new hand during the showdown assertions.
        registry.add("app.game.hand-result-delay-seconds", () -> "600");
        // Don't let a turn timer auto-act while the test drives the hand.
        registry.add("app.game.turn-action-timeout-seconds", () -> "600");
    }

    @Autowired
    private PokerGameService pokerGameService;

    @Autowired
    private GameHandLifecycleService lifecycleService;

    @Autowired
    private GameTurnTimeoutService turnTimeoutService;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @AfterEach
    void quiesce() {
        lifecycleService.cancelAll();
        turnTimeoutService.cancelAll();
    }

    @Test
    @DisplayName("a check/call hand runs to a five-card showdown — the deck survives every Redis round-trip")
    void multiStreetHandSurvivesHotState() {
        Game game = pokerGameService.createNewGame(List.of(
                new PlayerInfo("Alice", 1000, false),
                new PlayerInfo("Bob", 1000, false)));
        UUID gameId = game.getId();

        int maxBoard = 0;
        int guard = 0;
        while (!game.isFinished() && game.getPhase() != GamePhase.SHOWDOWN && guard++ < 40) {
            game = pokerGameService.getGame(gameId).orElseThrow();
            if (game.isFinished()) {
                break;
            }
            Player current = game.getCurrentPlayer();
            if (current == null) {
                break;
            }
            // Each Redis reload must carry the deck forward, or this action throws when the next street is dealt.
            PlayerAction action = current.getBetAmount() < game.getCurrentBet()
                    ? PlayerAction.CALL
                    : PlayerAction.CHECK;
            game = pokerGameService.playerAct(gameId, current.getId(), action, 0);
            maxBoard = Math.max(maxBoard, game.getCommunityCards().size());
        }

        Game finished = pokerGameService.getGame(gameId).orElseThrow();
        maxBoard = Math.max(maxBoard, finished.getCommunityCards().size());

        assertThat(finished.isFinished()).as("the hand reached showdown over hot-state").isTrue();
        assertThat(maxBoard).as("turn and river were dealt — the deck survived Redis round-trips").isEqualTo(5);
        assertThat(finished.getWinnerName()).as("a winner was determined at showdown").isNotNull();
        assertThat(finished.getPlayers().stream().mapToInt(Player::getChips).sum())
                .as("chips conserved").isEqualTo(2000);
    }
}
