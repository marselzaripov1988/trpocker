package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.domain.event.TournamentCompleted;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.PyramidBuyout;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentFeeEntry;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.repository.PyramidBuyoutRepository;
import com.truholdem.repository.TournamentFeeEntryRepository;
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
    private final PyramidBuyoutRepository buyoutRepository;
    private final TournamentFeeEntryRepository feeEntryRepository;

    public TournamentWalletService(WalletService walletService, TournamentService tournamentService,
            TournamentRegistrationRepository registrationRepository, TournamentRepository tournamentRepository,
            PyramidBuyoutRepository buyoutRepository, TournamentFeeEntryRepository feeEntryRepository) {
        this.walletService = walletService;
        this.tournamentService = tournamentService;
        this.registrationRepository = registrationRepository;
        this.tournamentRepository = tournamentRepository;
        this.buyoutRepository = buyoutRepository;
        this.feeEntryRepository = feeEntryRepository;
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
     * Cancel a tournament and, for a real-money one, credit every registrant's entry fee back to their wallet.
     * A player who bought a higher-level pyramid seat paid the seat <i>price</i> (which replaced the flat
     * buy-in), so they are refunded that price; everyone else is refunded the flat buy-in. Each refund is
     * idempotent per (tournament, user) — and the buy-out vs. flat refunds use distinct keys, so a buyer is
     * never refunded twice — so a re-run does not double-refund. Returns the number of entries refunded (0 for
     * play-money tournaments). Refund + cancel run in one transaction. Covers the "tournament never fills"
     * case, since an under-filled tournament is cancelled through this same path.
     */
    @Transactional
    public int cancelAndRefund(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException("Tournament not found: " + tournamentId));
        int refunded = 0;
        if (tournament.isRealMoney()) {
            CryptoAsset asset = tournament.getCryptoBuyInAsset();
            BigDecimal buyIn = tournament.getCryptoBuyInAmount();
            Map<UUID, PyramidBuyout> buyouts = new HashMap<>();
            for (PyramidBuyout b : buyoutRepository.findByTournamentId(tournamentId)) {
                buyouts.put(b.getBuyerPlayerId(), b);
            }
            for (TournamentRegistration reg : registrationRepository.findByTournamentId(tournamentId)) {
                UUID playerId = reg.getPlayerId();
                PyramidBuyout buyout = buyouts.get(playerId);
                boolean credited = buyout != null
                        ? walletService.refundBuyIn(playerId, buyout.getAsset(), buyout.getPriceAmount(),
                                buyoutRefundKey(tournamentId, playerId))
                        : walletService.refundBuyIn(playerId, asset, buyIn, refundKey(tournamentId, playerId));
                if (credited) {
                    refunded++;
                }
            }
        }
        tournamentService.cancelTournament(tournamentId);
        log.info("Tournament {} cancelled — refunded {} entry fee(s)", tournamentId, refunded);
        return refunded;
    }

    /**
     * Admin action: cancel a single player's registration and (for a real-money tournament) refund their
     * entry fee — the buy-out price if they bought a higher-level pyramid seat, otherwise the flat buy-in.
     * Uses the same idempotency key as {@link #cancelAndRefund}, so a later whole-tournament cancel never
     * double-refunds this player. Returns whether a refund was credited.
     */
    @Transactional
    public boolean cancelPlayerAndRefund(UUID tournamentId, UUID playerId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException("Tournament not found: " + tournamentId));
        boolean refunded = false;
        if (tournament.isRealMoney()) {
            PyramidBuyout buyout = buyoutRepository
                    .findByTournamentIdAndBuyerPlayerId(tournamentId, playerId).orElse(null);
            refunded = buyout != null
                    ? walletService.refundBuyIn(playerId, buyout.getAsset(), buyout.getPriceAmount(),
                            buyoutRefundKey(tournamentId, playerId))
                    : walletService.refundBuyIn(playerId, tournament.getCryptoBuyInAsset(),
                            tournament.getCryptoBuyInAmount(), refundKey(tournamentId, playerId));
        }
        tournamentService.adminCancelPlayerRegistration(tournamentId, playerId);
        log.info("Admin cancelled player {} in tournament {} (refunded={})", playerId, tournamentId, refunded);
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
        recordHouseFee(tournament);
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

    /**
     * Record the tournament's withheld house commission as revenue, once per tournament (idempotent on
     * {@code tfee:<id>}). The fee is not moved — the prize pool the finishers split is already net of it (see
     * {@link Tournament#cryptoPrizePool()}); this is the accounting record. No-op for a 0% fee.
     */
    private void recordHouseFee(Tournament tournament) {
        BigDecimal fee = tournament.cryptoHouseFee();
        if (fee.signum() <= 0) {
            return;
        }
        String key = "tfee:" + tournament.getId();
        if (feeEntryRepository.existsByIdempotencyKey(key)) {
            return;
        }
        feeEntryRepository.save(new TournamentFeeEntry(
                TournamentFeeEntry.SourceType.TOURNAMENT, tournament.getId(), tournament.getCryptoBuyInAsset(),
                tournament.cryptoGrossPool(), fee, tournament.getFeeBasisPoints(), key));
        log.info("Tournament {} — recorded house fee {} {} ({} bps of {})", tournament.getId(), fee,
                tournament.getCryptoBuyInAsset(), tournament.getFeeBasisPoints(), tournament.cryptoGrossPool());
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

    private static String buyoutRefundKey(UUID tournamentId, UUID userId) {
        return "tbuyup-refund:" + tournamentId + ":" + userId;
    }
}
