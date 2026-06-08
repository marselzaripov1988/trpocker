package com.truholdem.service.game;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.repository.GameRepository;
import com.truholdem.service.cluster.StaleOwnershipException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Coordinates game reads/writes between Redis (hot path) and PostgreSQL (milestones).
 * Used by {@link GameStateService} as the core persistence routing layer.
 *
 * <p>Hot-state writes degrade gracefully: an <b>infrastructure</b> failure of the Redis write (Redis
 * unreachable / timed out → a {@link DataAccessException}) is counted on the
 * {@code truholdem.hotstate.write.failures} meter, logged, and swallowed so the player's action is still
 * persisted to PostgreSQL rather than failing with a 500. A {@link StaleOwnershipException} (a <i>correctness</i>
 * signal — the fencing token shows another node now owns the table) is never swallowed: it propagates so the
 * stale owner stops mutating. Serialization bugs ({@code IllegalStateException}) also propagate — they are real
 * defects, not transient infra blips.
 */
public class GameStateCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(GameStateCoordinator.class);

    private final GameRepository gameRepository;
    private final ObjectProvider<RedisGameStateStore> redisStore;
    private final ObjectProvider<AsyncGamePersistService> asyncPersistService;
    private final AppProperties appProperties;
    private final Counter hotStateWrites;
    private final Counter hotStateWriteFailures;

    public GameStateCoordinator(
            GameRepository gameRepository,
            ObjectProvider<RedisGameStateStore> redisStore,
            ObjectProvider<AsyncGamePersistService> asyncPersistService,
            AppProperties appProperties,
            MeterRegistry meterRegistry) {
        this.gameRepository = gameRepository;
        this.redisStore = redisStore;
        this.asyncPersistService = asyncPersistService;
        this.appProperties = appProperties;
        this.hotStateWrites = Counter.builder("truholdem.hotstate.writes")
                .description("Successful Redis hot-state writes (game state cached as authoritative live state).")
                .register(meterRegistry);
        this.hotStateWriteFailures = Counter.builder("truholdem.hotstate.write.failures")
                .description("Redis hot-state writes that failed on an infrastructure error and fell back to "
                        + "PostgreSQL. A non-zero rate means the cluster hot-state is degraded — alert on it.")
                .register(meterRegistry);
        // Configured switch (app.game.hot-state-enabled) vs. actually-wired state. They diverge exactly in the
        // class of bug that silently disabled hot-state in production: enabled=1 but active=0. Alert on that gap
        // (it never false-fires when hot-state is intentionally off, since then enabled=0 too).
        Gauge.builder("truholdem.hotstate.enabled", this,
                        c -> c.appProperties.getGame().isHotStateEnabled() ? 1.0 : 0.0)
                .description("1 if app.game.hot-state-enabled is set, else 0 (the intended configuration).")
                .register(meterRegistry);
        Gauge.builder("truholdem.hotstate.active", this, c -> c.isHotStateActive() ? 1.0 : 0.0)
                .description("1 if Redis hot-state is actually wired and serving writes, else 0. If enabled=1 "
                        + "but active=0, the hot-state beans failed to wire — Redis is silently bypassed.")
                .register(meterRegistry);
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
                        tryHotStateSave(game);
                    }
                    return game;
                })
                .orElseThrow(() -> new NoSuchElementException("Game not found: " + gameId));
    }

    /**
     * After a player action while the hand is still in progress.
     */
    public Game afterPlayerAction(Game game) {
        boolean hotStateOk = !isHotStateActive() || tryHotStateSave(game);
        // Defer the PostgreSQL write only when the hand state is safely held in Redis. If the hot-state write
        // failed (degraded), we must persist to PostgreSQL now so the action is not lost.
        if (hotStateOk && shouldDeferPostgresWrite()) {
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
                tryHotStateSave(game);
            }
            asyncPersistService.getObject().persistAsync(game);
            logger.debug("Queued async PostgreSQL persist for game {}", game.getId());
            return game;
        }
        Game saved = gameRepository.save(game);
        if (isHotStateActive()) {
            tryHotStateSave(saved);
        }
        return saved;
    }

    /** Synchronous persist — required when the game id is not yet assigned. */
    public Game persistFullSync(Game game) {
        Game saved = gameRepository.save(game);
        if (isHotStateActive()) {
            tryHotStateSave(saved);
        }
        return saved;
    }

    /**
     * Writes the game to the Redis hot-state store, degrading gracefully on an infrastructure failure.
     *
     * @return {@code true} if the hot-state write succeeded; {@code false} if it failed on a transient Redis
     *         infrastructure error (counted + logged) — the caller must then ensure a PostgreSQL write.
     * @throws StaleOwnershipException if the fenced write was rejected (another node owns the table now) — this
     *         is a correctness signal and is never swallowed.
     */
    private boolean tryHotStateSave(Game game) {
        try {
            redisStore.getObject().save(game);
            hotStateWrites.increment();
            return true;
        } catch (StaleOwnershipException e) {
            // Correctness, not infrastructure: the new owner must win. Propagate so this stale node aborts.
            throw e;
        } catch (DataAccessException e) {
            hotStateWriteFailures.increment();
            logger.warn("Hot-state Redis write failed for game {} — falling back to PostgreSQL "
                    + "(cluster hot-state degraded): {}", game.getId(), e.toString());
            return false;
        }
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
                // A pyramid round driven synchronously persists in-line; an async write on a separate
                // thread would race the driver's continued work on the same game (stale-version rollback).
                && !HandLifecycleScheduling.isSuppressed()
                && asyncPersistService.getIfAvailable() != null;
    }
}
