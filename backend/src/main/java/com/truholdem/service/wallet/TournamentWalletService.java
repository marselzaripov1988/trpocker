package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.repository.TournamentRegistrationRepository;
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

    public TournamentWalletService(WalletService walletService, TournamentService tournamentService,
            TournamentRegistrationRepository registrationRepository) {
        this.walletService = walletService;
        this.tournamentService = tournamentService;
        this.registrationRepository = registrationRepository;
    }

    @Transactional
    public TournamentRegistration buyIn(UUID userId, UUID tournamentId, String playerName,
            CryptoAsset asset, BigDecimal amount) {
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

    private static String buyInKey(UUID tournamentId, UUID userId) {
        return "tbuyin:" + tournamentId + ":" + userId;
    }

    private static String payoutKey(UUID tournamentId, UUID userId) {
        return "tpayout:" + tournamentId + ":" + userId;
    }
}
