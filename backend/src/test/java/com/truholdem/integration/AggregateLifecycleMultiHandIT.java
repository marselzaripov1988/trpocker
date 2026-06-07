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
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.service.GameHandLifecycleService;
import com.truholdem.service.PokerGameService;

/**
 * Phase C — proves the <b>automatic, lifecycle-timer-driven</b> next hand works end-to-end on the aggregate
 * engine. {@code FullGameFlowIT} covers the <i>manual</i> {@code startNewHand} path; here a finished hand must
 * deal the next one on its own through the async chain
 * {@code finalizeAggregateHand → GameHandLifecycleService.scheduleAfterHandCompleted → RESULT_DELAY →
 * startNextHandFromLifecycle → startNewHandViaAggregate}, with the Theme-1 {@code Game.finished} fix ensuring a
 * hand-done flag is not mistaken for a match-over flag on reconstitution.
 */
@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Aggregate engine — lifecycle-driven multi-hand")
class AggregateLifecycleMultiHandIT {

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
        // The point of this IT: drive every hand through the aggregate kernel, and shorten the between-hands
        // delay so the timer-driven transition is observable quickly.
        registry.add("app.game.engine", () -> "aggregate");
        registry.add("app.game.hand-result-delay-seconds", () -> "1");
    }

    @Autowired
    private PokerGameService pokerGameService;

    @Autowired
    private GameHandLifecycleService lifecycleService;

    @MockitoBean
    private SimpMessagingTemplate messagingTemplate;

    @MockitoBean
    private RedisTemplate<String, Object> redisTemplate;

    private UUID gameId;

    @BeforeEach
    void setUp() {
        lifecycleService.cancelAll();
    }

    @AfterEach
    void quiesce() {
        lifecycleService.cancelAll();
    }

    @Test
    @DisplayName("a finished hand auto-deals the next one via the lifecycle timer, chips conserved")
    void lifecycleDealsNextHandOnAggregate() {
        Game game = pokerGameService.createNewGame(List.of(
                new PlayerInfo("Alice", 1000, false),
                new PlayerInfo("Bob", 1000, false)));
        gameId = game.getId();
        int firstHandNumber = game.getHandNumber();

        // End hand 1 immediately: the player to act folds heads-up, so the other wins uncontested.
        Player toAct = game.getCurrentPlayer();
        game = pokerGameService.playerAct(gameId, toAct.getId(), PlayerAction.FOLD, 0);
        assertThat(game.isFinished()).as("hand 1 ends on the fold").isTrue();

        // No manual startNewHand: the lifecycle timer must deal hand 2 on its own.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Game current = pokerGameService.getGame(gameId).orElseThrow();
            assertThat(current.getHandNumber())
                    .as("lifecycle timer must advance to the next hand")
                    .isGreaterThan(firstHandNumber);
            assertThat(current.getPhase()).isEqualTo(GamePhase.PRE_FLOP);
            assertThat(current.isFinished()).isFalse();
        });

        Game nextHand = pokerGameService.getGame(gameId).orElseThrow();
        // Fresh hand dealt: two hole cards each, nobody folded, no community cards, chips conserved at 2000.
        nextHand.getPlayers().forEach(p -> {
            assertThat(p.getHand()).as("each player re-dealt two hole cards").hasSize(2);
            assertThat(p.isFolded()).isFalse();
        });
        assertThat(nextHand.getCommunityCards()).isEmpty();
        // Conservation across the auto-transition: stacks + the freshly posted blinds in the pot == the buy-ins.
        int inStacks = nextHand.getPlayers().stream().mapToInt(Player::getChips).sum();
        assertThat(inStacks + nextHand.getCurrentPot())
                .as("no chips created or destroyed across the auto-transition (stacks + pot)")
                .isEqualTo(2000);
        assertThat(nextHand.getCurrentPot()).as("blinds posted for the new hand").isGreaterThan(0);
    }
}
