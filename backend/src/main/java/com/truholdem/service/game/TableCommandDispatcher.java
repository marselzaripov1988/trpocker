package com.truholdem.service.game;

import com.truholdem.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Engine-migration Phase 2: single-writer per table.
 *
 * <p>Serializes every mutation for a given table ({@code gameId}) so load → mutate → persist runs
 * atomically relative to other commands for the same table, eliminating the lost-update / interleave
 * races of the lock-free path. Different tables run in parallel over a shared bounded thread pool
 * (so we never spawn one thread per table — this scales to thousands of concurrent tables).
 *
 * <p>Idempotency: each command carries a {@code commandId}; a per-table bounded TTL cache replays the
 * recorded result/exception for a duplicate id (kills double-clicks and duplicate WebSocket frames)
 * without re-running the handler.
 *
 * <p>This is single-node only; clustering / per-table owner election is a later phase. The whole
 * mechanism is gated by {@code app.game.single-writer-enabled} for fast rollback.
 */
@Component
public class TableCommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TableCommandDispatcher.class);

    /** Set while a worker thread is executing a command for a table; enables re-entrancy detection. */
    private static final ThreadLocal<UUID> CURRENT_GAME = new ThreadLocal<>();

    private final ConcurrentHashMap<UUID, GameChain> chains = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService sweeper;

    private final long awaitMillis;
    private final long dedupTtlMillis;
    private final int dedupMaxPerGame;

    private final Counter dedupHits;
    private final Counter rejections;
    private final Counter timeouts;

    public TableCommandDispatcher(AppProperties appProperties, MeterRegistry meterRegistry) {
        AppProperties.Game game = appProperties.getGame();
        this.awaitMillis = game.getSingleWriterAwaitMillis();
        this.dedupTtlMillis = game.getCommandDedupTtlMillis();
        this.dedupMaxPerGame = game.getCommandDedupMaxPerGame();

        int configured = game.getSingleWriterPoolSize();
        int size = configured > 0
                ? configured
                : Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

        this.pool = new ThreadPoolExecutor(
                size, size,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(game.getSingleWriterQueueCapacity()),
                namedDaemonFactory("table-writer-"),
                new ThreadPoolExecutor.AbortPolicy());
        this.pool.allowCoreThreadTimeOut(true);

        this.sweeper = Executors.newSingleThreadScheduledExecutor(namedDaemonFactory("table-writer-sweeper-"));
        long period = Math.max(1_000L, dedupTtlMillis);
        this.sweeper.scheduleWithFixedDelay(this::sweepIdleChains, period, period, TimeUnit.MILLISECONDS);

        this.dedupHits = Counter.builder("poker.table.command.dedup_hits")
                .description("Commands short-circuited by commandId idempotency").register(meterRegistry);
        this.rejections = Counter.builder("poker.table.command.rejections")
                .description("Commands rejected because the writer pool/queue was saturated").register(meterRegistry);
        this.timeouts = Counter.builder("poker.table.command.timeouts")
                .description("Commands that exceeded the single-writer await budget").register(meterRegistry);
        Gauge.builder("poker.table.chains.active", chains, Map::size)
                .description("Tables with an active single-writer chain").register(meterRegistry);

        log.info("TableCommandDispatcher ready: poolSize={}, queueCapacity={}, awaitMs={}, dedupTtlMs={}, dedupMax={}",
                size, game.getSingleWriterQueueCapacity(), awaitMillis, dedupTtlMillis, dedupMaxPerGame);
    }

    /**
     * Run {@code handler} as the next command on {@code gameId}'s single-writer chain, blocking for its
     * result. A duplicate {@code commandId} replays the recorded outcome instead of re-running the
     * handler. Exceptions thrown by the handler are rethrown to the caller with their original type
     * preserved (so the controller exception → HTTP mapping is unchanged).
     *
     * @param commandId may be {@code null}; a random id is used (no idempotency) in that case.
     */
    public <T> T submit(UUID gameId, UUID commandId, Supplier<T> handler) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(handler, "handler");
        UUID cmdId = (commandId != null) ? commandId : UUID.randomUUID();

        // Re-entrancy guard: a handler already running on this table's worker thread must run inline,
        // otherwise it would enqueue behind itself and deadlock.
        if (gameId.equals(CURRENT_GAME.get())) {
            return handler.get();
        }

        // Fast-path idempotency (best effort; the authoritative check is inside the serialized task).
        GameChain chain = chains.computeIfAbsent(gameId, id -> new GameChain(dedupMaxPerGame, dedupTtlMillis));
        Outcome cached = chain.dedup.get(cmdId);
        if (cached != null) {
            dedupHits.increment();
            return replay(cached);
        }

        CompletableFuture<Object> result;
        try {
            result = enqueue(gameId, cmdId, handler);
        } catch (RejectedExecutionException rejected) {
            throw onRejected(gameId, rejected);
        }

        try {
            @SuppressWarnings("unchecked")
            T value = (T) result.get(awaitMillis, TimeUnit.MILLISECONDS);
            return value;
        } catch (ExecutionException e) {
            throw rethrow(gameId, e.getCause());
        } catch (TimeoutException e) {
            timeouts.increment();
            throw new IllegalStateException("Timed out waiting for table " + gameId + " command after "
                    + awaitMillis + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for table " + gameId + " command", e);
        }
    }

    /** Parse a client-supplied commandId; returns {@code null} for null/blank/malformed input. */
    public static UUID parseCommandId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** True when the current thread is executing a command for {@code gameId} (test/observability aid). */
    public boolean isOnGameThread(UUID gameId) {
        return gameId != null && gameId.equals(CURRENT_GAME.get());
    }

    /** Number of tables with a live chain (test/observability aid). */
    public int activeChainCount() {
        return chains.size();
    }

    private CompletableFuture<Object> enqueue(UUID gameId, UUID cmdId, Supplier<?> handler) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        // compute() is atomic per key, so chain.tail updates and idle-eviction never interleave.
        chains.compute(gameId, (id, existing) -> {
            GameChain chain = (existing != null) ? existing : new GameChain(dedupMaxPerGame, dedupTtlMillis);
            CompletableFuture<Object> prev = chain.tail;
            // handleAsync ignores prev's outcome, so a failed command never poisons the chain.
            CompletableFuture<Object> stage = prev
                    .handleAsync((r, t) -> runGuarded(gameId, chain, cmdId, handler), pool)
                    .whenComplete((value, error) -> {
                        if (error != null) {
                            result.completeExceptionally(unwrap(error));
                        } else {
                            result.complete(value);
                        }
                    });
            chain.tail = stage;
            chain.lastActivityMillis = System.currentTimeMillis();
            return chain;
        });
        return result;
    }

    private Object runGuarded(UUID gameId, GameChain chain, UUID cmdId, Supplier<?> handler) {
        Outcome cached = chain.dedup.get(cmdId);
        if (cached != null) {
            dedupHits.increment();
            return replay(cached);
        }
        UUID previous = CURRENT_GAME.get();
        CURRENT_GAME.set(gameId);
        long now = System.currentTimeMillis();
        try {
            Object value = handler.get();
            chain.dedup.put(cmdId, Outcome.success(value, now));
            return value;
        } catch (RuntimeException | Error ex) {
            chain.dedup.put(cmdId, Outcome.failure(ex, now));
            throw ex;
        } finally {
            if (previous != null) {
                CURRENT_GAME.set(previous);
            } else {
                CURRENT_GAME.remove();
            }
        }
    }

    private void sweepIdleChains() {
        long now = System.currentTimeMillis();
        for (UUID id : chains.keySet()) {
            chains.computeIfPresent(id, (key, chain) -> {
                boolean idle = chain.tail.isDone() && (now - chain.lastActivityMillis) > dedupTtlMillis;
                return idle ? null : chain;
            });
        }
    }

    private RuntimeException onRejected(UUID gameId, RejectedExecutionException rejected) {
        rejections.increment();
        return new IllegalStateException("Table " + gameId + " writer is saturated; command rejected", rejected);
    }

    @SuppressWarnings("unchecked")
    private <T> T replay(Outcome outcome) {
        if (outcome.throwable != null) {
            if (outcome.throwable instanceof RuntimeException re) {
                throw re;
            }
            throw (Error) outcome.throwable;
        }
        return (T) outcome.value;
    }

    private RuntimeException rethrow(UUID gameId, Throwable cause) {
        if (cause instanceof RejectedExecutionException rejected) {
            return onRejected(gameId, rejected);
        }
        if (cause instanceof RuntimeException re) {
            return re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new IllegalStateException("Table " + gameId + " command failed", cause);
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof CompletionException && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /** Per-table state: the serial chain tail plus the bounded idempotency cache. */
    private static final class GameChain {
        private volatile CompletableFuture<Object> tail = CompletableFuture.completedFuture(null);
        private volatile long lastActivityMillis = System.currentTimeMillis();
        private final DedupCache dedup;

        GameChain(int max, long ttlMillis) {
            this.dedup = new DedupCache(max, ttlMillis);
        }
    }

    /** Access-order LRU map, size-bounded, with lazy TTL expiry on lookup. */
    private static final class DedupCache {
        private final int max;
        private final long ttlMillis;
        private final LinkedHashMap<UUID, Outcome> map;

        DedupCache(int max, long ttlMillis) {
            this.max = max;
            this.ttlMillis = ttlMillis;
            this.map = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Outcome> eldest) {
                    return size() > DedupCache.this.max;
                }
            };
        }

        synchronized Outcome get(UUID id) {
            Outcome outcome = map.get(id);
            if (outcome == null) {
                return null;
            }
            if (System.currentTimeMillis() - outcome.timestamp > ttlMillis) {
                map.remove(id);
                return null;
            }
            return outcome;
        }

        synchronized void put(UUID id, Outcome outcome) {
            map.put(id, outcome);
        }
    }

    /** Recorded result of a command: either a value or a thrown unchecked throwable. */
    private static final class Outcome {
        private final Object value;
        private final Throwable throwable;
        private final long timestamp;

        private Outcome(Object value, Throwable throwable, long timestamp) {
            this.value = value;
            this.throwable = throwable;
            this.timestamp = timestamp;
        }

        static Outcome success(Object value, long timestamp) {
            return new Outcome(value, null, timestamp);
        }

        static Outcome failure(Throwable throwable, long timestamp) {
            return new Outcome(null, throwable, timestamp);
        }
    }
}
