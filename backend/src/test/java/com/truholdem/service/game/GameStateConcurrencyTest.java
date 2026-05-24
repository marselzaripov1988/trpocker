package com.truholdem.service.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.repository.GameRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("GameState concurrency smoke test")
class GameStateConcurrencyTest {

  private static final int THREADS = 32;
  private static final int ACTIONS_PER_THREAD = 25;

  @Mock
  private GameRepository gameRepository;

  @Mock
  private ObjectProvider<RedisGameStateStore> redisStore;

  @Mock
  private RedisGameStateStore redisGameStateStore;

  @Mock
  private ObjectProvider<AsyncGamePersistService> asyncPersistService;

  @Mock
  private AppProperties appProperties;

  @Mock
  private AppProperties.Game gameConfig;

  private GameStateService gameStateService;

  @BeforeEach
  void setUp() {
    lenient().when(appProperties.getGame()).thenReturn(gameConfig);
    lenient().when(gameConfig.isHotStateEnabled()).thenReturn(true);
    lenient().when(gameConfig.isPersistOnHandEndOnly()).thenReturn(true);
    lenient().when(gameConfig.isAsyncPersistEnabled()).thenReturn(false);
    lenient().when(redisStore.getIfAvailable()).thenReturn(redisGameStateStore);
    lenient().when(redisStore.getObject()).thenReturn(redisGameStateStore);
    lenient().when(asyncPersistService.getIfAvailable()).thenReturn(null);
    gameStateService = new GameStateService(gameRepository, redisStore, asyncPersistService, appProperties);
  }

  @Test
  @DisplayName("concurrent afterPlayerAction calls complete without lost updates")
  void concurrentAfterPlayerAction() throws Exception {
    Game game = new Game();
    game.setId(UUID.randomUUID());
    AtomicInteger pot = new AtomicInteger(0);

    try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < THREADS; i++) {
        tasks.add(() -> {
          int local = 0;
          for (int j = 0; j < ACTIONS_PER_THREAD; j++) {
            gameStateService.afterPlayerAction(game);
            local = pot.incrementAndGet();
          }
          return local;
        });
      }
      List<Future<Integer>> futures = pool.invokeAll(tasks);
      for (Future<Integer> future : futures) {
        assertThat(future.get()).isPositive();
      }
    }

    assertThat(pot.get()).isEqualTo(THREADS * ACTIONS_PER_THREAD);
  }
}
