package com.truholdem.service.game;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

    public AsyncGamePersistService(
            GameRepository gameRepository,
            ObjectProvider<RedisGameStateStore> redisStore) {
        this.gameRepository = gameRepository;
        this.redisStore = redisStore;
    }

    @Async("gamePersistExecutor")
    @Transactional
    public void persistAsync(Game game) {
        if (game == null || game.getId() == null) {
            return;
        }
        try {
            Game saved = gameRepository.save(game);
            RedisGameStateStore store = redisStore.getIfAvailable();
            if (store != null) {
                store.save(saved);
            }
            logger.debug("Async persisted game {} to PostgreSQL", saved.getId());
        } catch (Exception e) {
            logger.error("Async persist failed for game {}", game.getId(), e);
        }
    }

    @Async("gamePersistExecutor")
    @Transactional
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
