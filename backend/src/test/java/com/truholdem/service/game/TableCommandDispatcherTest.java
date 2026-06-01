package com.truholdem.service.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.config.AppProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@DisplayName("TableCommandDispatcher — single-writer + idempotency")
class TableCommandDispatcherTest {

    private TableCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        // defaults are fine; keep TTL generous so dedup entries survive the test
        dispatcher = new TableCommandDispatcher(props, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        dispatcher.shutdown();
    }

    @Test
    @DisplayName("serializes all commands for one table — no lost updates, never two at once")
    void sameTableIsSerialized() throws InterruptedException {
        UUID gameId = UUID.randomUUID();
        int threads = 32;
        int[] sharedCounter = {0};
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService clients = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                clients.submit(() -> {
                    ready.countDown();
                    await(go);
                    dispatcher.submit(gameId, UUID.randomUUID(), () -> {
                        int now = inFlight.incrementAndGet();
                        maxInFlight.accumulateAndGet(now, Math::max);
                        // non-atomic read-modify-write: only safe if truly serialized
                        int value = sharedCounter[0];
                        sleep(1);
                        sharedCounter[0] = value + 1;
                        inFlight.decrementAndGet();
                        return null;
                    });
                });
            }
            await(ready);
            go.countDown();
        } finally {
            clients.shutdown();
            assertThat(clients.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(sharedCounter[0]).isEqualTo(threads);
        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("different tables run in parallel (not globally serialized)")
    void differentTablesRunInParallel() throws InterruptedException {
        int tables = 4; // <= pool size
        CountDownLatch allStarted = new CountDownLatch(tables);
        CountDownLatch results = new CountDownLatch(tables);
        AtomicInteger succeeded = new AtomicInteger();
        ExecutorService clients = Executors.newFixedThreadPool(tables);
        try {
            for (int i = 0; i < tables; i++) {
                UUID gameId = UUID.randomUUID();
                clients.submit(() -> {
                    Boolean ok = dispatcher.submit(gameId, UUID.randomUUID(), () -> {
                        allStarted.countDown();
                        // proceeds only if every table's command runs concurrently
                        return await(allStarted, 5);
                    });
                    if (Boolean.TRUE.equals(ok)) {
                        succeeded.incrementAndGet();
                    }
                    results.countDown();
                });
            }
            assertThat(results.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            clients.shutdownNow();
        }
        assertThat(succeeded.get()).isEqualTo(tables);
    }

    @Test
    @DisplayName("duplicate commandId replays the first result without re-running the handler")
    void duplicateCommandIdIsIdempotent() {
        UUID gameId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        String first = dispatcher.submit(gameId, commandId, () -> {
            calls.incrementAndGet();
            return "first";
        });
        String second = dispatcher.submit(gameId, commandId, () -> {
            calls.incrementAndGet();
            return "second";
        });

        assertThat(first).isEqualTo("first");
        assertThat(second).isEqualTo("first"); // replayed
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a new commandId runs the handler again")
    void distinctCommandIdsRunSeparately() {
        UUID gameId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        dispatcher.submit(gameId, UUID.randomUUID(), () -> calls.incrementAndGet());
        dispatcher.submit(gameId, UUID.randomUUID(), () -> calls.incrementAndGet());

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("handler exception is rethrown with its original type (not ExecutionException)")
    void exceptionIsRethrownUnwrapped() {
        UUID gameId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> dispatcher.submit(gameId, commandId, () -> {
            calls.incrementAndGet();
            throw new NoSuchElementException("missing");
        })).isInstanceOf(NoSuchElementException.class).hasMessage("missing");

        // the failure is cached too: same commandId replays the same exception, handler not re-run
        assertThatThrownBy(() -> dispatcher.submit(gameId, commandId, () -> {
            calls.incrementAndGet();
            return "should-not-run";
        })).isInstanceOf(NoSuchElementException.class).hasMessage("missing");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-entrant submit for the same table runs inline (no deadlock)")
    void reentrantSubmitDoesNotDeadlock() {
        UUID gameId = UUID.randomUUID();

        String result = dispatcher.submit(gameId, UUID.randomUUID(),
                () -> dispatcher.submit(gameId, UUID.randomUUID(), () -> "inner"));

        assertThat(result).isEqualTo("inner");
    }

    @Test
    @DisplayName("parseCommandId tolerates null/blank/malformed input")
    void parseCommandIdIsLenient() {
        assertThat(TableCommandDispatcher.parseCommandId(null)).isNull();
        assertThat(TableCommandDispatcher.parseCommandId("  ")).isNull();
        assertThat(TableCommandDispatcher.parseCommandId("not-a-uuid")).isNull();
        UUID id = UUID.randomUUID();
        assertThat(TableCommandDispatcher.parseCommandId(id.toString())).isEqualTo(id);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(15, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean await(CountDownLatch latch, int seconds) {
        try {
            return latch.await(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
