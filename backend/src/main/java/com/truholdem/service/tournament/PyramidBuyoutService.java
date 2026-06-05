package com.truholdem.service.tournament;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.AppProperties;
import com.truholdem.model.PyramidBuyout;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentStatus;
import com.truholdem.repository.PyramidBuyoutRepository;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.service.wallet.WalletService;

/**
 * Pre-start higher-level seat buy-outs for a buy-up pyramid. A registered player buys one seat at a higher
 * level for the sub-pyramid price ({@code seatsPerTable^(L-1) × buyIn}); paying it <b>replaces</b> the flat
 * buy-in (the base buy-in is refunded and the seat price charged, so the net cost is the seat price). A seat is
 * buyable only while its sub-pyramid is empty — its level-1 range is entirely above the registration frontier
 * (registrations fill level-1 bottom-up) and does not overlap another buy-out. Limited to one buy-out per
 * player (DB-enforced) and {@code app.tournament.pyramid-max-buyouts} per tournament.
 */
@Service
public class PyramidBuyoutService {

    private static final Logger log = LoggerFactory.getLogger(PyramidBuyoutService.class);

    private final PyramidBuyoutRepository buyoutRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentRegistrationRepository registrationRepository;
    private final WalletService walletService;
    private final AppProperties appProperties;

    public PyramidBuyoutService(PyramidBuyoutRepository buyoutRepository,
            TournamentRepository tournamentRepository,
            TournamentRegistrationRepository registrationRepository,
            WalletService walletService, AppProperties appProperties) {
        this.buyoutRepository = buyoutRepository;
        this.tournamentRepository = tournamentRepository;
        this.registrationRepository = registrationRepository;
        this.walletService = walletService;
        this.appProperties = appProperties;
    }

    /** A buyable "ticket": one higher-level seat the player could buy, with its price. */
    public record BuyoutTicket(int level, int seatIndex, BigDecimal price) {
    }

    /** All currently-buyable seats across all levels, with prices, for the player to choose ("tickets"). */
    @Transactional(readOnly = true)
    public List<BuyoutTicket> availableTickets(UUID tournamentId) {
        Tournament tournament = requireBuyUp(tournamentId);
        PyramidBracket bracket = bracketFor(tournament);
        long registered = registrationRepository.countByTournamentId(tournamentId);
        List<PyramidBuyout> existing = buyoutRepository.findByTournamentId(tournamentId);

        List<BuyoutTicket> tickets = new ArrayList<>();
        for (int level = 2; level <= bracket.levels(); level++) {
            BigDecimal price = bracket.buyoutPrice(level, tournament.getCryptoBuyInAmount());
            long seats = bracket.buyableSeatsAtLevel(level);
            for (int seat = 0; seat < seats; seat++) {
                if (isBuyable(bracket, level, seat, registered, existing)) {
                    tickets.add(new BuyoutTicket(level, seat, price));
                }
            }
        }
        return tickets;
    }

    /** Buy a higher-level seat. Validates the rules, replaces the buy-in (refund base + charge price), records it. */
    @Transactional
    public PyramidBuyout buySeat(UUID tournamentId, UUID playerId, int level, int seatIndex) {
        Tournament tournament = requireBuyUp(tournamentId);
        if (tournament.getStatus() != TournamentStatus.REGISTERING) {
            throw new IllegalStateException("Buy-outs are only allowed before the tournament starts");
        }
        if (registrationRepository.findByTournamentIdAndPlayerId(tournamentId, playerId).isEmpty()) {
            throw new IllegalStateException("Only a registered player can buy a seat");
        }
        PyramidBracket bracket = bracketFor(tournament);
        if (level < 2 || level > bracket.levels()) {
            throw new IllegalArgumentException("level out of range: " + level);
        }
        if (seatIndex < 0 || seatIndex >= bracket.buyableSeatsAtLevel(level)) {
            throw new IllegalArgumentException("seatIndex out of range for level " + level + ": " + seatIndex);
        }
        if (buyoutRepository.countByTournamentId(tournamentId) >= appProperties.getTournament().getPyramidMaxBuyouts()) {
            throw new IllegalStateException("Buy-out limit reached for this tournament");
        }
        if (buyoutRepository.existsByTournamentIdAndBuyerPlayerId(tournamentId, playerId)) {
            throw new IllegalStateException("Player has already bought a seat");
        }
        long registered = registrationRepository.countByTournamentId(tournamentId);
        List<PyramidBuyout> existing = buyoutRepository.findByTournamentId(tournamentId);
        if (!isBuyable(bracket, level, seatIndex, registered, existing)) {
            throw new IllegalStateException(
                    "Seat is not buyable (sub-pyramid not empty, already taken, or overlapping a buy-out)");
        }

        BigDecimal base = tournament.getCryptoBuyInAmount();
        BigDecimal price = bracket.buyoutPrice(level, base);
        // Replace the flat buy-in: return the base entry fee, then charge the seat price.
        walletService.refundBuyIn(playerId, tournament.getCryptoBuyInAsset(), base,
                "tbuyin-refund:" + tournamentId + ":" + playerId);
        walletService.chargeBuyIn(playerId, tournament.getCryptoBuyInAsset(), price,
                "tbuyup:" + tournamentId + ":" + playerId);

        PyramidBuyout buyout = buyoutRepository.save(new PyramidBuyout(
                tournamentId, playerId, level, seatIndex, price, tournament.getCryptoBuyInAsset()));
        log.info("Player {} bought pyramid seat L{}#{} in tournament {} for {} {}",
                playerId, level, seatIndex, tournamentId, price, tournament.getCryptoBuyInAsset());
        return buyout;
    }

    private boolean isBuyable(PyramidBracket bracket, int level, int seatIndex, long registered,
            List<PyramidBuyout> existing) {
        // The sub-pyramid must be entirely above the registration frontier (level-1 seats fill bottom-up).
        if (bracket.subtreeStart(level, seatIndex) < registered) {
            return false;
        }
        for (PyramidBuyout b : existing) {
            if (b.getLevel() == level && b.getSeatIndex() == seatIndex) {
                return false; // already taken
            }
            if (bracket.overlaps(level, seatIndex, b.getLevel(), b.getSeatIndex())) {
                return false; // overlaps another bought sub-pyramid
            }
        }
        return true;
    }

    private Tournament requireBuyUp(UUID tournamentId) {
        Tournament tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException("Tournament not found: " + tournamentId));
        if (!tournament.isPyramidBuyUpEnabled()) {
            throw new IllegalStateException("Tournament " + tournamentId + " is not a buy-up pyramid");
        }
        if (!tournament.isRealMoney()) {
            throw new IllegalStateException("Buy-up pyramid requires a real-money buy-in");
        }
        return tournament;
    }

    private static PyramidBracket bracketFor(Tournament tournament) {
        return new PyramidBracket(tournament.getMaxPlayers(), tournament.getSeatsPerTable());
    }
}
