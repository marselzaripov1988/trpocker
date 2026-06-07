package com.truholdem.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.model.HandLifecycleState;
import com.truholdem.service.cluster.TableOwnershipService;
import com.truholdem.service.game.HandLifecycleScheduling;

@Service
public class GameHandLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(GameHandLifecycleService.class);

    private final TaskScheduler taskScheduler;
    private final AppProperties appProperties;
    private final PokerGameService pokerGameService;
    private final TableOwnershipService ownership;
    private final Map<UUID, ScheduledFuture<?>> scheduledTransitions = new ConcurrentHashMap<>();

    public GameHandLifecycleService(
            TaskScheduler taskScheduler,
            AppProperties appProperties,
            @Lazy PokerGameService pokerGameService,
            TableOwnershipService ownership) {
        this.taskScheduler = taskScheduler;
        this.appProperties = appProperties;
        this.pokerGameService = pokerGameService;
        this.ownership = ownership;
    }

    public void scheduleAfterHandCompleted(Game game) {
        if (game == null
                || game.getId() == null
                || !game.isFinished()
                || game.getHandLifecycleState() != HandLifecycleState.HAND_COMPLETED) {
            return;
        }

        // A PYRAMID round driven synchronously (simulation / admin advance) progresses hands itself;
        // the live lifecycle timer would race that driver, so it is suppressed on the driver thread.
        if (HandLifecycleScheduling.isSuppressed()) {
            return;
        }

        UUID gameId = game.getId();
        int handNumber = game.getHandNumber();
        cancel(gameId);

        // Only the owning node drives this table's hand-lifecycle transitions.
        if (!ownership.acquire(gameId)) {
            return;
        }

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> transitionToResultDelay(gameId, handNumber),
                Instant.now().plusMillis(100));

        scheduledTransitions.put(gameId, future);
        log.debug("Scheduled hand lifecycle transition to RESULT_DELAY for game {} hand {}",
                gameId, handNumber);
    }

    public void cancel(UUID gameId) {
        if (gameId == null) {
            return;
        }

        ScheduledFuture<?> existing = scheduledTransitions.remove(gameId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    /**
     * Cancel every pending between-hands transition. Used for graceful shutdown and for test isolation so a
     * timer scheduled by one scenario cannot fire (and mutate / re-persist a game) during the next one's cleanup.
     * Does not interrupt a transition already executing; callers that need full quiescence should also wait for
     * {@link #pendingTransitionCount()} to settle.
     */
    public void cancelAll() {
        scheduledTransitions.values().forEach(future -> future.cancel(false));
        scheduledTransitions.clear();
    }

    /** Number of between-hands transitions currently scheduled (not yet fired or cancelled). */
    public int pendingTransitionCount() {
        return scheduledTransitions.size();
    }

    /**
     * Resume a pending between-hands transition after this node takes over an orphaned table whose
     * previous owner died with the timer pending. Each branch re-checks the lifecycle state and only
     * the owning node proceeds, so it is safe to call unconditionally on takeover.
     *
     * <p>No-op for {@code IN_PROGRESS} (the turn timer drives that) and for the transient
     * {@code NEXT_HAND} state (set synchronously within {@code startNextHandFromLifecycle}; a crash in
     * that narrow window is not resumed here).
     */
    public void resumePendingTransition(Game game) {
        if (game == null || game.getId() == null || game.getHandLifecycleState() == null) {
            return;
        }
        switch (game.getHandLifecycleState()) {
            case HAND_COMPLETED -> scheduleAfterHandCompleted(game);
            case RESULT_DELAY -> scheduleNextHand(game);
            default -> {
                // IN_PROGRESS → turn timer; NEXT_HAND → transient, not resumable here
            }
        }
    }

    private void transitionToResultDelay(UUID gameId, int handNumber) {
        if (!ownership.isOwner(gameId)) {
            return; // lease moved to another node
        }
        try {
            Game delayed = pokerGameService.transitionCompletedHandToResultDelay(gameId, handNumber);
            scheduleNextHand(delayed);
        } catch (RuntimeException e) {
            log.warn("Failed to transition game {} hand {} to RESULT_DELAY", gameId, handNumber, e);
        }
    }

    private void scheduleNextHand(Game game) {
        if (game == null
                || game.getId() == null
                || game.getHandLifecycleState() != HandLifecycleState.RESULT_DELAY) {
            return;
        }

        UUID gameId = game.getId();
        int handNumber = game.getHandNumber();
        int delaySeconds = appProperties.getGame().getHandResultDelaySeconds();

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> startNextHand(gameId, handNumber),
                Instant.now().plusSeconds(delaySeconds));

        scheduledTransitions.put(gameId, future);
        log.debug("Scheduled NEXT_HAND for game {} hand {} after {}s",
                gameId, handNumber, delaySeconds);
    }

    private void startNextHand(UUID gameId, int handNumber) {
        scheduledTransitions.remove(gameId);
        if (!ownership.isOwner(gameId)) {
            return; // lease moved to another node
        }
        try {
            java.util.Optional<Game> next = pokerGameService.startNextHandFromLifecycle(gameId, handNumber);
            if (next.isEmpty()) {
                // game is over (not enough players) — stop owning this table so its lease can expire
                ownership.release(gameId);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to start next hand for game {} after hand {}", gameId, handNumber, e);
        }
    }
}
