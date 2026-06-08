package com.truholdem.service.game;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.repository.GameRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Spring facade for {@link GameStateCoordinator} (Redis hot state + PostgreSQL milestones).
 */
@Service
public class GameStateService {

    private final GameStateCoordinator coordinator;

    public GameStateService(
            GameRepository gameRepository,
            ObjectProvider<RedisGameStateStore> redisStore,
            ObjectProvider<AsyncGamePersistService> asyncPersistService,
            AppProperties appProperties,
            MeterRegistry meterRegistry) {
        this.coordinator = new GameStateCoordinator(
                gameRepository, redisStore, asyncPersistService, appProperties, meterRegistry);
    }

    public Game load(UUID gameId) {
        return coordinator.load(gameId);
    }

    public Game afterPlayerAction(Game game) {
        return coordinator.afterPlayerAction(game);
    }

    public Game persistFull(Game game) {
        return coordinator.persistFull(game);
    }

    public Game persistFullSync(Game game) {
        return coordinator.persistFullSync(game);
    }
}
