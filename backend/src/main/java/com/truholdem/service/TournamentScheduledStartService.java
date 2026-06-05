package com.truholdem.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.service.wallet.TournamentWalletService;

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
    private final TournamentWalletService tournamentWalletService;
    private final AppProperties appProperties;

    public TournamentScheduledStartService(TournamentService tournamentService,
            TournamentWalletService tournamentWalletService, AppProperties appProperties) {
        this.tournamentService = tournamentService;
        this.tournamentWalletService = tournamentWalletService;
        this.appProperties = appProperties;
    }

    @Scheduled(fixedDelayString = "${app.tournament.scheduled-start-interval-ms:30000}")
    public void startDueTournaments() {
        if (!appProperties.getTournament().isScheduledStartEnabled()) {
            return;
        }
        for (UUID id : tournamentService.dueForScheduledStart(Instant.now())) {
            try {
                processDue(id);
            } catch (RuntimeException e) {
                log.warn("Scheduled start of tournament {} failed (will retry next tick)", id, e);
            }
        }
    }

    private void processDue(UUID id) {
        int registered = tournamentService.registeredCount(id);
        if (tournamentService.requiresFullToStart(id)) {
            int seats = tournamentService.maxPlayers(id);
            if (registered >= seats) {
                tournamentService.startTournament(id);
                log.info("Auto-started full scheduled tournament {} ({}/{} seats)", id, registered, seats);
            } else {
                tournamentService.postponeToNextDay(id); // under-filled at its slot → next day's slot
            }
            return;
        }
        int minPlayers = tournamentService.minPlayers(id);
        if (registered >= minPlayers) {
            tournamentService.startTournament(id);
            log.info("Auto-started scheduled tournament {} ({} players)", id, registered);
        } else if (appProperties.getTournament().isCancelUnderfilledScheduled()) {
            int refunded = tournamentWalletService.cancelAndRefund(id);
            log.info("Scheduled tournament {} under-filled ({}/{}) — cancelled, refunded {} buy-in(s)",
                    id, registered, minPlayers, refunded);
        } else {
            log.warn("Scheduled tournament {} is due but has {}/{} players — leaving REGISTERING",
                    id, registered, minPlayers);
        }
    }
}
