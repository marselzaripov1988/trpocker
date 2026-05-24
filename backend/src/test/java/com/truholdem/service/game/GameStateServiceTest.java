package com.truholdem.service.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

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
@DisplayName("GameStateService Tests")
class GameStateServiceTest {

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

    private GameStateService gameStateService;

    @BeforeEach
    void setUp() {
        lenient().when(appProperties.getGame()).thenReturn(gameConfig);
        lenient().when(redisStore.getIfAvailable()).thenReturn(redisGameStateStore);
        lenient().when(redisStore.getObject()).thenReturn(redisGameStateStore);
        lenient().when(asyncPersistService.getIfAvailable()).thenReturn(null);
        gameStateService = new GameStateService(gameRepository, redisStore, asyncPersistService, appProperties);
    }

    @Test
    @DisplayName("service delegates load to coordinator (Redis hit)")
    void load_delegatesToCoordinator() {
        when(gameConfig.isHotStateEnabled()).thenReturn(true);
        UUID gameId = UUID.randomUUID();
        Game cached = new Game();
        cached.setId(gameId);
        when(redisGameStateStore.find(gameId)).thenReturn(Optional.of(cached));

        Game loaded = gameStateService.load(gameId);

        assertThat(loaded).isSameAs(cached);
    }
}
