package com.truholdem.service.cluster;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.service.GameHandLifecycleService;
import com.truholdem.service.GameTurnTimeoutService;
import com.truholdem.service.PokerGameService;

/**
 * Phase 5 failover takeover: periodically scans the cluster's active-table set and takes over any table
 * whose owner has died (its Redis lease expired, so it has no current owner). On takeover this node
 * re-acquires ownership and resumes the stalled turn timer, so a game does not hang waiting on a player
 * that the dead owner was supposed to time out.
 *
 * <p>Without this, an orphaned table only recovers lazily — on the next action for that table, whichever
 * node receives it re-claims ownership. That is fine when someone acts, but a table sitting on a pending
 * turn timeout would stall until the timed-out player (or another) happened to act.
 *
 * <p>Gated by {@code app.cluster.takeover-enabled} (requires {@code ownership-enabled}); inert otherwise.
 * On takeover both the turn timer (for an in-progress hand) and the between-hands transition (for a table
 * orphaned in {@code HAND_COMPLETED}/{@code RESULT_DELAY}) are resumed; each is state-guarded so exactly the
 * applicable one fires.
 */
@Service
public class ClusterFailoverService {

    private static final Logger log = LoggerFactory.getLogger(ClusterFailoverService.class);

    private final AppProperties appProperties;
    private final TableOwnershipService ownership;
    private final PokerGameService pokerGameService;
    private final GameTurnTimeoutService turnTimeoutService;
    private final GameHandLifecycleService handLifecycleService;

    public ClusterFailoverService(
            AppProperties appProperties,
            TableOwnershipService ownership,
            @Lazy PokerGameService pokerGameService,
            GameTurnTimeoutService turnTimeoutService,
            GameHandLifecycleService handLifecycleService) {
        this.appProperties = appProperties;
        this.ownership = ownership;
        this.pokerGameService = pokerGameService;
        this.turnTimeoutService = turnTimeoutService;
        this.handLifecycleService = handLifecycleService;
    }

    /** Scan twice per lease so an orphaned table is taken over within roughly one lease TTL of the death. */
    @Scheduled(fixedDelayString = "#{${app.cluster.lease-ttl-millis:30000} / 2}")
    public void takeOverOrphanedTables() {
        AppProperties.Cluster cluster = appProperties.getCluster();
        if (!cluster.isTakeoverEnabled() || !cluster.isOwnershipEnabled()) {
            return;
        }
        Set<UUID> tables = ownership.activeTables();
        for (UUID gameId : tables) {
            try {
                takeOverIfOrphaned(gameId);
            } catch (RuntimeException e) {
                log.warn("Failover takeover failed for table {}", gameId, e);
            }
        }
    }

    /** Returns true if this node took the table over (for tests). */
    public boolean takeOverIfOrphaned(UUID gameId) {
        if (ownership.currentOwner(gameId) != null) {
            return false; // still has a live owner
        }
        if (!ownership.acquire(gameId)) {
            return false; // another node won the takeover race
        }

        Game game = pokerGameService.getGame(gameId).orElse(null);
        if (game == null) {
            ownership.release(gameId); // game no longer in shared state → prune the active-set entry + lease
            return false;
        }
        // NB: do NOT prune on game.isFinished() — that flag means "current hand finished", which is also
        // true between hands. A genuinely-over game is already removed from the active set by the
        // next-hand path (which releases when there are too few players to continue).

        log.info("Took over orphaned table {} (previous owner gone); resuming timers", gameId);
        // Both are state-guarded internally, so exactly the applicable one acts:
        //  - in-progress hand → re-arm the current player's turn timer;
        //  - between hands (HAND_COMPLETED/RESULT_DELAY) → resume the next-hand transition.
        turnTimeoutService.scheduleForCurrentTurn(game);
        handLifecycleService.resumePendingTransition(game);
        return true;
    }
}
