package com.truholdem.service.game;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.repository.GameRepository;

/**
 * Coordinates game reads/writes between Redis (hot path) and PostgreSQL (milestones).
 * Used by {@link GameStateService} as the core persistence routing layer.
 */
public class GameStateCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(GameStateCoordinator.class);

    private final GameRepository gameRepository;
    private final ObjectProvider<RedisGameStateStore> redisStore;
    private final ObjectProvider<AsyncGamePersistService> asyncPersistService;
    private final AppProperties appProperties;

    public GameStateCoordinator(
            GameRepository gameRepository,
            ObjectProvider<RedisGameStateStore> redisStore,
            ObjectProvider<AsyncGamePersistService> asyncPersistService,
            AppProperties appProperties) {
        this.gameRepository = gameRepository;
        this.redisStore = redisStore;
        this.asyncPersistService = asyncPersistService;
        this.appProperties = appProperties;
    }

    public Game load(UUID gameId) {
        if (isHotStateActive()) {
            Optional<Game> cached = redisStore.getObject().find(gameId);
            if (cached.isPresent()) {
                return cached.get();
            }
            logger.debug("Game {} cache miss — loading from database", gameId);
        }
        return gameRepository.findById(gameId)
                .map(game -> {
                    if (isHotStateActive()) {
                        redisStore.getObject().save(game);
                    }
                    return game;
                })
                .orElseThrow(() -> new NoSuchElementException("Game not found: " + gameId));
    }

    /**
     * After a player action while the hand is still in progress.
     */
    public Game afterPlayerAction(Game game) {
        if (isHotStateActive()) {
            redisStore.getObject().save(game);
        }
        if (shouldDeferPostgresWrite()) {
            return game;
        }
        return gameRepository.save(game);
    }

    /**
     * Full persist: hand end, new hand, game creation.
     */
    public Game persistFull(Game game) {
        if (shouldAsyncPersist(game)) {
            if (isHotStateActive()) {
                redisStore.getObject().save(game);
            }
            asyncPersistService.getObject().persistAsync(game);
            logger.debug("Queued async PostgreSQL persist for game {}", game.getId());
            return game;
        }
        Game saved = gameRepository.save(game);
        if (isHotStateActive()) {
            redisStore.getObject().save(saved);
        }
        return saved;
    }

    /** Synchronous persist — required when the game id is not yet assigned. */
    public Game persistFullSync(Game game) {
        Game saved = gameRepository.save(game);
        if (isHotStateActive()) {
            redisStore.getObject().save(saved);
        }
        return saved;
    }

    private boolean isHotStateActive() {
        return appProperties.getGame().isHotStateEnabled() && redisStore.getIfAvailable() != null;
    }

    private boolean shouldDeferPostgresWrite() {
        return isHotStateActive() && appProperties.getGame().isPersistOnHandEndOnly();
    }

    private boolean shouldAsyncPersist(Game game) {
        return game.getId() != null
                && appProperties.getGame().isAsyncPersistEnabled()
                && asyncPersistService.getIfAvailable() != null;
    }
}
