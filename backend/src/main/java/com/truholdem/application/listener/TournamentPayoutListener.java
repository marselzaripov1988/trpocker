package com.truholdem.application.listener;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.domain.event.TournamentCompleted;
import com.truholdem.service.wallet.TournamentWalletService;

/**
 * Auto-payout: when a real-money tournament completes, credit each in-the-money finisher's crypto wallet
 * with their share of the crypto prize pool. Listens to {@link TournamentCompleted} (fired once at the end,
 * carrying every top finisher incl. the winner) and delegates to {@link TournamentWalletService}, which
 * loads the tournament, computes each share, and credits best-effort + idempotently (so a re-fired event
 * or retry does not double-credit). No-op for play-money tournaments / when payments are disabled.
 */
@Component
public class TournamentPayoutListener {

    private final TournamentWalletService tournamentWalletService;
    private final AppProperties appProperties;

    public TournamentPayoutListener(TournamentWalletService tournamentWalletService,
            AppProperties appProperties) {
        this.tournamentWalletService = tournamentWalletService;
        this.appProperties = appProperties;
    }

    @EventListener
    public void onTournamentCompleted(TournamentCompleted event) {
        if (!appProperties.getPayments().isEnabled()) {
            return;
        }
        tournamentWalletService.payoutOnCompletion(event.getTournamentId(), event.getTopFinishers());
    }
}
