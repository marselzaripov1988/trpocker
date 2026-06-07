package com.truholdem.service.game;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.model.Game;
import com.truholdem.repository.GameRepository;

@Service
public class AsyncGamePersistService {

    private static final Logger logger = LoggerFactory.getLogger(AsyncGamePersistService.class);

    private final GameRepository gameRepository;
    private final ObjectProvider<RedisGameStateStore> redisStore;
    private final AsyncGamePersistService self;

    public AsyncGamePersistService(
            GameRepository gameRepository,
            ObjectProvider<RedisGameStateStore> redisStore,
            @Lazy AsyncGamePersistService self) {
        this.gameRepository = gameRepository;
        this.redisStore = redisStore;
        this.self = self;
    }

    @Async("gamePersistExecutor")
    public void persistAsync(Game game) {
        if (game == null || game.getId() == null) {
            return;
        }
        try {
            // The write runs in its own transaction (through the proxy) so an optimistic-lock conflict rolls that
            // transaction back cleanly instead of poisoning this async method and surfacing as an
            // UnexpectedRollbackException at commit.
            self.saveInNewTransaction(game);
            logger.debug("Async persisted game {} to PostgreSQL", game.getId());
        } catch (ObjectOptimisticLockingFailureException e) {
            // Hot-state Redis is the authoritative live state; PostgreSQL is a lagging mirror. A concurrent mirror
            // write (e.g. a hand-end finalize racing the result-delay transition) already advanced the row —
            // benign, the next boundary write re-persists the latest state. Expected, so logged quietly.
            logger.debug("Async DB persist for game {} superseded by a newer write — skipping", game.getId());
        } catch (Exception e) {
            logger.error("Async persist failed for game {}", game.getId(), e);
        }
    }

    /**
     * Persist the game and refresh the hot-state cache in a dedicated transaction. Public so the self-proxy applies
     * {@code @Transactional}; not intended to be called directly from outside this bean.
     */
    @Transactional
    public void saveInNewTransaction(Game game) {
        Game saved = gameRepository.save(game);
        RedisGameStateStore store = redisStore.getIfAvailable();
        if (store != null) {
            store.save(saved);
        }
    }

    @Async("gamePersistExecutor")
    public void persistByIdAsync(UUID gameId) {
        if (gameId == null) {
            return;
        }
        RedisGameStateStore store = redisStore.getIfAvailable();
        if (store == null) {
            logger.warn("Async persist by id skipped — no Redis store for game {}", gameId);
            return;
        }
        store.find(gameId).ifPresentOrElse(
                this::persistAsync,
                () -> logger.warn("Async persist by id — game {} not found in Redis", gameId));
    }
}
