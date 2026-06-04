package com.truholdem.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;

/**
 * Periodically auto-starts tournaments whose {@code scheduledStart} time has passed, provided they have at
 * least {@code minPlayers} registered (typically MTT / scheduled freezeouts). Under-filled tournaments are
 * left REGISTERING and logged — auto-cancel + buy-in refund is a separate concern. Cluster-safe to run on
 * every node: {@link TournamentService#startTournament} guards on the tournament status and the entity's
 * optimistic-lock version, so a concurrent double-start has one winner and the loser's transaction rolls back.
 * Inert unless {@code app.tournament.scheduled-start-enabled=true}.
 */
@Component
public class TournamentScheduledStartService {

    private static final Logger log = LoggerFactory.getLogger(TournamentScheduledStartService.class);

    private final TournamentService tournamentService;
    private final AppProperties appProperties;

    public TournamentScheduledStartService(TournamentService tournamentService, AppProperties appProperties) {
        this.tournamentService = tournamentService;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelayString = "${app.tournament.scheduled-start-interval-ms:30000}")
    public void startDueTournaments() {
        if (!appProperties.getTournament().isScheduledStartEnabled()) {
            return;
        }
        for (UUID id : tournamentService.dueForScheduledStart(Instant.now())) {
            try {
                int registered = tournamentService.registeredCount(id);
                int minPlayers = tournamentService.minPlayers(id);
                if (registered >= minPlayers) {
                    tournamentService.startTournament(id);
                    log.info("Auto-started scheduled tournament {} ({} players)", id, registered);
                } else {
                    log.warn("Scheduled tournament {} is due but has {}/{} players — leaving REGISTERING",
                            id, registered, minPlayers);
                }
            } catch (RuntimeException e) {
                log.warn("Scheduled start of tournament {} failed (will retry next tick)", id, e);
            }
        }
    }
}
