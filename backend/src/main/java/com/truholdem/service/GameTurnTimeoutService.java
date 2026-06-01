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
import com.truholdem.model.Player;
import com.truholdem.service.cluster.TableOwnershipService;

@Service
public class GameTurnTimeoutService {

    private static final Logger log = LoggerFactory.getLogger(GameTurnTimeoutService.class);

    private final TaskScheduler taskScheduler;
    private final AppProperties appProperties;
    private final PokerGameService pokerGameService;
    private final TableOwnershipService ownership;
    private final Map<UUID, ScheduledFuture<?>> scheduledTimeouts = new ConcurrentHashMap<>();

    public GameTurnTimeoutService(
            TaskScheduler taskScheduler,
            AppProperties appProperties,
            @Lazy PokerGameService pokerGameService,
            TableOwnershipService ownership) {
        this.taskScheduler = taskScheduler;
        this.appProperties = appProperties;
        this.pokerGameService = pokerGameService;
        this.ownership = ownership;
    }

    public void scheduleForCurrentTurn(Game game) {
        if (game == null || game.getId() == null) {
            return;
        }

        cancel(game.getId());

        Player currentPlayer = game.getCurrentPlayer();
        if (game.isFinished()
                || game.getHandLifecycleState() != HandLifecycleState.IN_PROGRESS
                || currentPlayer == null
                || currentPlayer.isBot()
                || !currentPlayer.canAct()) {
            return;
        }

        UUID gameId = game.getId();
        // Only the owning node schedules this table's timer (no double-fire on a cluster).
        if (!ownership.acquire(gameId)) {
            return;
        }

        int timeoutSeconds = appProperties.getGame().getTurnActionTimeoutSeconds();
        UUID playerId = currentPlayer.getId();
        String phase = game.getPhase().name();
        int currentBet = game.getCurrentBet();
        int communityCardCount = game.getCommunityCards().size();

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> {
                    if (!ownership.isOwner(gameId)) {
                        return; // lease moved to another node before firing
                    }
                    pokerGameService.handleTurnTimeout(
                            gameId,
                            playerId,
                            phase,
                            currentBet,
                            communityCardCount);
                },
                Instant.now().plusSeconds(timeoutSeconds));

        scheduledTimeouts.put(gameId, future);
        // Record the table as active so a surviving node can take it over if this node dies mid-turn.
        ownership.trackActiveTable(gameId);
        log.debug("Scheduled {}s turn timeout for player {} in game {}",
                timeoutSeconds, playerId, gameId);
    }

    public void cancel(UUID gameId) {
        if (gameId == null) {
            return;
        }

        ScheduledFuture<?> existing = scheduledTimeouts.remove(gameId);
        if (existing != null) {
            existing.cancel(false);
        }
    }
}
