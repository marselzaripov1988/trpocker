package com.truholdem.application.listener;

import com.truholdem.domain.event.HandCompleted;
import com.truholdem.domain.event.PlayerActed;
import com.truholdem.domain.event.PlayerEliminated;
import com.truholdem.domain.event.PotAwarded;
import com.truholdem.service.PlayerStatisticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Phase 3 (CQRS): derives player statistics from domain events instead of imperative service calls.
 *
 * <p>Handlers are deliberately <strong>synchronous</strong> (no {@code @Async}): events for one hand
 * must be processed in publication order and within the action's transaction, mirroring the previous
 * imperative behavior. {@code PlayerActed} feeds the per-action buffer in {@link PlayerStatisticsService}
 * (flushed at hand end by the game service); {@code HandCompleted} records wins/showdowns. Wins are
 * recorded only from {@code HandCompleted} — {@code PotAwarded} stays log-only to avoid double counting.
 *
 * <p>Active only on the aggregate engine path ({@code app.game.engine=AGGREGATE}), which publishes
 * these events; the legacy path keeps recording statistics imperatively.
 */
@Component
public class StatisticsEventListener {

    private static final Logger log = LoggerFactory.getLogger(StatisticsEventListener.class);

    private final PlayerStatisticsService statisticsService;

    public StatisticsEventListener(PlayerStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @EventListener
    public void onPlayerActed(PlayerActed event) {
        try {
            // POST_SMALL_BLIND / POST_BIG_BLIND / ALL_IN are no-ops in the stats action switch,
            // matching the prior imperative behavior (only voluntary BET/RAISE/CALL/FOLD/CHECK count).
            statisticsService.recordAction(event.getPlayerName(), event.getAction().name());
            if (event.isAllIn()) {
                statisticsService.recordAllIn(event.getPlayerName());
            }
        } catch (Exception e) {
            log.error("Failed to process PlayerActed event for statistics: {}", event, e);
        }
    }

    @EventListener
    public void onHandCompleted(HandCompleted event) {
        try {
            for (HandCompleted.PotResult potResult : event.getPotResults()) {
                statisticsService.recordWin(potResult.winnerName(), potResult.amount().amount());
                if (event.wentToShowdown()) {
                    statisticsService.recordShowdown(potResult.winnerName(), true);
                }
            }
        } catch (Exception e) {
            log.error("Failed to process HandCompleted event for statistics: {}", event, e);
        }
    }

    /** Win is already recorded from {@link HandCompleted}; kept log-only to avoid double counting. */
    @EventListener
    public void onPotAwarded(PotAwarded event) {
        log.debug("PotAwarded: {} wins {} {}", event.getWinnerName(), event.getPotType(), event.getAmount());
    }

    /** Tournament finish stats are out of scope for this slice. */
    @EventListener
    public void onPlayerEliminated(PlayerEliminated event) {
        log.debug("PlayerEliminated: {} finished {}", event.getPlayerName(), event.getPositionDisplay());
    }
}
