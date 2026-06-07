package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerInfo;
import com.truholdem.service.GameHandLifecycleService;
import com.truholdem.service.GameTurnTimeoutService;
import com.truholdem.service.PokerGameService;

/**
 * Phase C — proves the turn-action timeout auto-acts through the <b>aggregate</b> engine. When the player on the
 * clock never acts, {@code GameTurnTimeoutService} fires {@code PokerGameService.handleTurnTimeout}, which routes
 * (via {@code playerActInternal → playerActViaAggregate}) into the aggregate kernel and auto-folds the player
 * facing a bet. Heads-up, that ends the hand. The result delay is set very high so the lifecycle does not deal a
 * new hand mid-assertion, keeping the timed-out state observable.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Aggregate engine — turn-action timeout auto-folds")
class AggregateTurnTimeoutIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("truholdem_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

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
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("app.jwt.secret", () -> "dGVzdC1zZWNyZXQta2V5LWZvci1pbnRlZ3JhdGlvbi10ZXN0cy0xMjM0NTY3ODkw");
        registry.add("app.jwt.expiration", () -> "86400000");
        registry.add("app.game.engine", () -> "aggregate");
        // Fire the turn timer quickly; keep the result delay long so the next hand isn't dealt during assertions.
        registry.add("app.game.turn-action-timeout-seconds", () -> "1");
        registry.add("app.game.hand-result-delay-seconds", () -> "600");
    }

    @Autowired
    private PokerGameService pokerGameService;

    @Autowired
    private GameTurnTimeoutService turnTimeoutService;

    @Autowired
    private GameHandLifecycleService lifecycleService;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        turnTimeoutService.cancelAll();
        lifecycleService.cancelAll();
    }

    @AfterEach
    void quiesce() {
        turnTimeoutService.cancelAll();
        lifecycleService.cancelAll();
    }

    @Test
    @DisplayName("a player who never acts is auto-folded by the timer through the aggregate kernel")
    void turnTimeoutAutoFoldsOnAggregate() {
        Game game = pokerGameService.createNewGame(List.of(
                new PlayerInfo("Idle", 1000, false),
                new PlayerInfo("Waiter", 1000, false)));
        UUID gameId = game.getId();

        Player onTheClock = game.getCurrentPlayer();
        assertThat(onTheClock).as("a player is on the clock at deal").isNotNull();
        UUID idleId = onTheClock.getId();
        assertThat(game.isFinished()).isFalse();

        // Do nothing: the 1s turn timer must auto-fold the idle player (facing the big blind) and end the hand.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Game current = pokerGameService.getGame(gameId).orElseThrow();
            assertThat(current.isFinished()).as("turn timeout must end the heads-up hand").isTrue();
        });

        Game finished = pokerGameService.getGame(gameId).orElseThrow();
        Player idle = finished.getPlayers().stream()
                .filter(p -> p.getId().equals(idleId))
                .findFirst()
                .orElseThrow();
        assertThat(idle.isFolded()).as("the idle player was auto-folded").isTrue();
        assertThat(finished.getWinnerName()).as("the other player won uncontested").isNotNull();
        assertThat(finished.getPlayers().stream().mapToInt(Player::getChips).sum())
                .as("chips conserved after the auto-fold").isEqualTo(2000);
    }
}
