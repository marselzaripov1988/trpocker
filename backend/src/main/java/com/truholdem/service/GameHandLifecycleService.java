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
