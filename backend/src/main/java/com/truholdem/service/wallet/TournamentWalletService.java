package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.domain.event.TournamentCompleted;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.service.TournamentService;

/**
 * Bridge between the real-money crypto wallet and (play-money) tournaments: a buy-in debits the player's
 * {@link com.truholdem.model.WalletAccount} and registers them; a payout credits it. The crypto charge and
 * the tournament registration happen in one transaction, so if registration fails (full / already
 * registered / not open) the debit rolls back. Charges are idempotent per (tournament, user), so a repeated
 * buy-in neither double-charges nor double-registers.
 */
@Service
public class TournamentWalletService {

    private static final Logger log = LoggerFactory.getLogger(TournamentWalletService.class);

    private final WalletService walletService;
    private final TournamentService tournamentService;
    private final TournamentRegistrationRepository registrationRepository;
    private final TournamentRepository tournamentRepository;

    public TournamentWalletService(WalletService walletService, TournamentService tournamentService,
            TournamentRegistrationRepository registrationRepository, TournamentRepository tournamentRepository) {
        this.walletService = walletService;
        this.tournamentService = tournamentService;
        this.registrationRepository = registrationRepository;
        this.tournamentRepository = tournamentRepository;
    }

    /** Buy into a real-money tournament at its configured crypto fee (debit + register, atomically). */
    @Transactional
    public TournamentRegistration buyIn(UUID userId, UUID tournamentId, String playerName) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException("Tournament not found: " + tournamentId));
        if (!tournament.isRealMoney()) {
            throw new IllegalStateException("Tournament " + tournamentId + " is not a real-money tournament");
        }
        CryptoAsset asset = tournament.getCryptoBuyInAsset();
        BigDecimal amount = tournament.getCryptoBuyInAmount();
        boolean charged = walletService.chargeBuyIn(userId, asset, amount, buyInKey(tournamentId, userId));
        if (!charged) {
            // Already bought in (idempotent) — return the existing registration without re-charging.
            return registrationRepository.findByTournamentIdAndPlayerId(tournamentId, userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Buy-in already charged but no registration for user " + userId));
        }
        TournamentRegistration registration = tournamentService.registerPlayer(tournamentId, userId, playerName);
        log.info("User {} bought into tournament {} for {} {}", userId, tournamentId, amount, asset);
        return registration;
    }

    @Transactional
    public boolean payout(UUID userId, UUID tournamentId, CryptoAsset asset, BigDecimal amount) {
        return walletService.awardPayout(userId, asset, amount, payoutKey(tournamentId, userId));
    }

    /**
     * Cancel a tournament and, for a real-money one, credit every registrant's buy-in back to their wallet.
     * The refund is idempotent per (tournament, user), so a re-run does not double-refund. Returns the number
     * of buy-ins refunded (0 for play-money tournaments). Refund + cancel run in one transaction.
     */
    @Transactional
    public int cancelAndRefund(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException("Tournament not found: " + tournamentId));
        int refunded = 0;
        if (tournament.isRealMoney()) {
            CryptoAsset asset = tournament.getCryptoBuyInAsset();
            BigDecimal amount = tournament.getCryptoBuyInAmount();
            for (TournamentRegistration reg : registrationRepository.findByTournamentId(tournamentId)) {
                if (walletService.refundBuyIn(reg.getPlayerId(), asset, amount,
                        refundKey(tournamentId, reg.getPlayerId()))) {
                    refunded++;
                }
            }
        }
        tournamentService.cancelTournament(tournamentId);
        log.info("Tournament {} cancelled — refunded {} buy-in(s)", tournamentId, refunded);
        return refunded;
    }

    /**
     * Credit every in-the-money finisher of a completed real-money tournament with their crypto share.
     * No-op for play-money / unknown tournaments. Best-effort per finisher (a failed credit is logged, not
     * fatal) and idempotent via {@link #payout}. Returns the number of finishers actually credited.
     */
    @Transactional
    public int payoutOnCompletion(UUID tournamentId, List<TournamentCompleted.FinishResult> finishers) {
        Tournament tournament = tournamentRepository.findById(tournamentId).orElse(null);
        if (tournament == null || !tournament.isRealMoney()) {
            return 0;
        }
        int credited = 0;
        for (TournamentCompleted.FinishResult finisher : finishers) {
            BigDecimal prize = tournament.cryptoPrizeForPosition(finisher.position());
            if (prize.signum() <= 0) {
                continue;
            }
            try {
                if (payout(finisher.playerId(), tournamentId, tournament.getCryptoBuyInAsset(), prize)) {
                    credited++;
                }
                log.info("Auto-payout: tournament {} position {} → {} {} to {}", tournamentId,
                        finisher.position(), prize, tournament.getCryptoBuyInAsset(), finisher.playerId());
            } catch (RuntimeException e) {
                log.warn("Auto-payout failed for tournament {} finisher {} (position {})", tournamentId,
                        finisher.playerId(), finisher.position(), e);
            }
        }
        return credited;
    }

    private static String buyInKey(UUID tournamentId, UUID userId) {
        return "tbuyin:" + tournamentId + ":" + userId;
    }

    private static String payoutKey(UUID tournamentId, UUID userId) {
        return "tpayout:" + tournamentId + ":" + userId;
    }

    private static String refundKey(UUID tournamentId, UUID userId) {
        return "trefund:" + tournamentId + ":" + userId;
    }
}
