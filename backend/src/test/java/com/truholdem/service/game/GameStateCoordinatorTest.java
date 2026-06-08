package com.truholdem.service.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.repository.GameRepository;
import com.truholdem.service.cluster.StaleOwnershipException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
@DisplayName("GameStateCoordinator Tests")
class GameStateCoordinatorTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private ObjectProvider<RedisGameStateStore> redisStore;

    @Mock
    private RedisGameStateStore redisGameStateStore;

    @Mock
    private AppProperties appProperties;

    @Mock
    private AppProperties.Game gameConfig;

    @Mock
    private ObjectProvider<AsyncGamePersistService> asyncPersistService;

    @Mock
    private AsyncGamePersistService asyncGamePersistService;

    private GameStateCoordinator coordinator;

    @BeforeEach
    void setUp() {
        lenient().when(appProperties.getGame()).thenReturn(gameConfig);
        lenient().when(redisStore.getIfAvailable()).thenReturn(redisGameStateStore);
        lenient().when(redisStore.getObject()).thenReturn(redisGameStateStore);
        lenient().when(asyncPersistService.getIfAvailable()).thenReturn(null);
        lenient().when(gameConfig.isAsyncPersistEnabled()).thenReturn(false);
        coordinator = new GameStateCoordinator(
                gameRepository, redisStore, asyncPersistService, appProperties, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("afterPlayerAction defers PostgreSQL when hot state and persist-on-hand-end are enabled")
    void afterPlayerAction_defersPostgresWhenConfigured() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        when(gameConfig.isPersistOnHandEndOnly()).thenReturn(true);

        Game game = new Game();
        game.setId(UUID.randomUUID());

        Game result = coordinator.afterPlayerAction(game);

        assertThat(result).isSameAs(game);
        verify(redisGameStateStore).save(game);
        verify(gameRepository, never()).save(any());
    }

    @Test
    @DisplayName("afterPlayerAction writes PostgreSQL when hot state is disabled")
    void afterPlayerAction_writesPostgresWhenHotStateDisabled() {
        when(gameConfig.isHotStateEnabled()).thenReturn(false);
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Game game = new Game();
        game.setId(UUID.randomUUID());

        coordinator.afterPlayerAction(game);

        verify(gameRepository).save(game);
        verify(redisGameStateStore, never()).save(any());
    }

    @Test
    @DisplayName("afterPlayerAction writes both stores when hot state is on but deferral is off")
    void afterPlayerAction_writesBothWhenHotStateWithoutDeferral() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        when(gameConfig.isPersistOnHandEndOnly()).thenReturn(false);
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Game game = new Game();
        game.setId(UUID.randomUUID());

        coordinator.afterPlayerAction(game);

        verify(redisGameStateStore).save(game);
        verify(gameRepository).save(game);
    }

    @Test
    @DisplayName("afterPlayerAction skips Redis when hot state is enabled but Redis bean is unavailable")
    void afterPlayerAction_skipsRedisWhenStoreUnavailable() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        when(redisStore.getIfAvailable()).thenReturn(null);
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Game game = new Game();
        game.setId(UUID.randomUUID());

        coordinator.afterPlayerAction(game);

        verify(redisGameStateStore, never()).save(any());
        verify(gameRepository).save(game);
    }

    @Test
    @DisplayName("load reads Redis first on cache hit")
    void load_usesRedisWhenPresent() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        UUID gameId = UUID.randomUUID();
        Game cached = new Game();
        cached.setId(gameId);
        when(redisGameStateStore.find(gameId)).thenReturn(Optional.of(cached));

        Game loaded = coordinator.load(gameId);

        assertThat(loaded).isSameAs(cached);
        verify(gameRepository, never()).findById(any());
    }

    @Test
    @DisplayName("load falls back to PostgreSQL and warms Redis on cache miss")
    void load_fallsBackToDatabaseAndWarmsCache() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        UUID gameId = UUID.randomUUID();
        Game fromDb = new Game();
        fromDb.setId(gameId);
        when(redisGameStateStore.find(gameId)).thenReturn(Optional.empty());
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(fromDb));

        Game loaded = coordinator.load(gameId);

        assertThat(loaded).isSameAs(fromDb);
        verify(redisGameStateStore).save(fromDb);
    }

    @Test
    @DisplayName("load reads PostgreSQL only when hot state is disabled")
    void load_usesDatabaseOnlyWhenHotStateDisabled() {
        when(gameConfig.isHotStateEnabled()).thenReturn(false);
        UUID gameId = UUID.randomUUID();
        Game fromDb = new Game();
        fromDb.setId(gameId);
        when(gameRepository.findById(gameId)).thenReturn(Optional.of(fromDb));

        Game loaded = coordinator.load(gameId);

        assertThat(loaded).isSameAs(fromDb);
        verify(redisGameStateStore, never()).find(any());
        verify(redisGameStateStore, never()).save(any());
    }

    @Test
    @DisplayName("load throws when game is missing")
    void load_throwsWhenNotFound() {
        when(gameConfig.isHotStateEnabled()).thenReturn(false);
        UUID gameId = UUID.randomUUID();
        when(gameRepository.findById(gameId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.load(gameId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(gameId.toString());
    }

    @Test
    @DisplayName("persistFull writes PostgreSQL and refreshes Redis")
    void persistFull_writesBothStores() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        Game game = new Game();
        game.setId(UUID.randomUUID());
        when(gameRepository.save(game)).thenReturn(game);

        coordinator.persistFull(game);

        verify(gameRepository).save(game);
        verify(redisGameStateStore).save(game);
    }

    @Test
    @DisplayName("persistFull queues async PostgreSQL when async persist is enabled")
    void persistFull_queuesAsyncWhenConfigured() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        when(gameConfig.isAsyncPersistEnabled()).thenReturn(true);
        when(asyncPersistService.getIfAvailable()).thenReturn(asyncGamePersistService);
        when(asyncPersistService.getObject()).thenReturn(asyncGamePersistService);

        Game game = new Game();
        game.setId(UUID.randomUUID());

        Game result = coordinator.persistFull(game);

        assertThat(result).isSameAs(game);
        verify(redisGameStateStore).save(game);
        verify(asyncGamePersistService).persistAsync(game);
        verify(gameRepository, never()).save(any());
    }

    @Test
    @DisplayName("persistFullSync always writes PostgreSQL and refreshes Redis when hot state is on")
    void persistFullSync_writesBothStores() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        Game game = new Game();
        when(gameRepository.save(game)).thenReturn(game);

        Game result = coordinator.persistFullSync(game);

        assertThat(result).isSameAs(game);
        verify(gameRepository).save(game);
        verify(redisGameStateStore).save(game);
    }

    @Test
    @DisplayName("afterPlayerAction degrades to PostgreSQL when the Redis hot-state write fails (action not lost)")
    void afterPlayerAction_degradesToPostgresOnRedisFailure() {
        // Hot state on: a failed Redis write must short-circuit the deferral and still persist to PostgreSQL
        // rather than failing the player's move. (isPersistOnHandEndOnly is never consulted once the hot-state
        // write fails — hotStateOk=false short-circuits the deferral check.)
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("redis down")).when(redisGameStateStore).save(any());
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Game game = new Game();
        game.setId(UUID.randomUUID());

        Game result = coordinator.afterPlayerAction(game);

        assertThat(result).isSameAs(game);
        verify(redisGameStateStore).save(game);
        verify(gameRepository).save(game); // fell back to PostgreSQL — the action survives the Redis outage
    }

    @Test
    @DisplayName("a fenced-write rejection (StaleOwnershipException) propagates — never swallowed as degradation")
    void hotStateSave_propagatesStaleOwnership() {
        // A stale-ownership rejection is a correctness signal (another node owns the table now), not a transient
        // infra blip — it must abort the action, not silently fall back to a local PostgreSQL write.
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        UUID gameId = UUID.randomUUID();
        doThrow(new StaleOwnershipException(gameId)).when(redisGameStateStore).save(any());

        Game game = new Game();
        game.setId(gameId);

        assertThatThrownBy(() -> coordinator.afterPlayerAction(game))
                .isInstanceOf(StaleOwnershipException.class);
        verify(gameRepository, never()).save(any());
    }
}
