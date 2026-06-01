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
 * Note (v1): only the turn timer is resumed. A table orphaned <em>between hands</em> (NEXT_HAND timer on
 * the dead owner) gets a live owner again but its next-hand transition is not proactively resumed — that
 * remains a documented follow-up.
 */
@Service
public class ClusterFailoverService {

    private static final Logger log = LoggerFactory.getLogger(ClusterFailoverService.class);

    private final AppProperties appProperties;
    private final TableOwnershipService ownership;
    private final PokerGameService pokerGameService;
    private final GameTurnTimeoutService turnTimeoutService;

    public ClusterFailoverService(
            AppProperties appProperties,
            TableOwnershipService ownership,
            @Lazy PokerGameService pokerGameService,
            GameTurnTimeoutService turnTimeoutService) {
        this.appProperties = appProperties;
        this.ownership = ownership;
        this.pokerGameService = pokerGameService;
        this.turnTimeoutService = turnTimeoutService;
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
        if (game == null || game.isFinished()) {
            ownership.release(gameId); // truly done / gone → prune from the active set + drop the lease
            return false;
        }

        log.info("Took over orphaned table {} (previous owner gone); resuming turn timer", gameId);
        // Re-arms the turn timer for the current human turn (no-op for a bot turn / between hands).
        turnTimeoutService.scheduleForCurrentTurn(game);
        return true;
    }
}
