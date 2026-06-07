package com.truholdem.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.domain.event.DomainEvent;
import com.truholdem.domain.event.PotAwarded;
import com.truholdem.domain.value.Chips;
import com.truholdem.model.CashChipScale;
import com.truholdem.model.CashSeat;
import com.truholdem.model.CashSeatStatus;
import com.truholdem.model.CashTable;
import com.truholdem.model.GamePhase;
import com.truholdem.model.PlayerInfo;
import com.truholdem.model.Player;
import com.truholdem.repository.CashSeatRepository;
import com.truholdem.repository.CashTableRepository;
import com.truholdem.service.wallet.CashGameWalletService;

/**
 * Drives a cash (ring) hand on the pure {@link PokerGame} aggregate kernel and settles it back to the seats and
 * the wallet. The table's real-money stakes/stacks are mapped to the engine's integer chips via
 * {@link CashChipScale}; after the hand finishes the rake is taken from each awarded pot (recorded as house
 * revenue), the players' final stacks are written back to their {@link CashSeat}s in money, and any seat that
 * asked to leave is cashed out. Tournaments are untouched — they keep the default legacy engine.
 *
 * <p>This slice provides the start + settlement seam; persisting the live hand between actions and cluster
 * hot-state for an indefinitely-live table are later slices. Each seated player maps to an engine player by
 * name (the kernel assigns fresh player ids on create), so a table's live seats must have distinct names.
 */
@Service
public class CashGameService {

    private static final Logger log = LoggerFactory.getLogger(CashGameService.class);

    private final CashTableRepository cashTableRepository;
    private final CashSeatRepository cashSeatRepository;
    private final CashRakeService rakeService;
    private final CashGameWalletService walletBridge;

    public CashGameService(CashTableRepository cashTableRepository, CashSeatRepository cashSeatRepository,
            CashRakeService rakeService, CashGameWalletService walletBridge) {
        this.cashTableRepository = cashTableRepository;
        this.cashSeatRepository = cashSeatRepository;
        this.rakeService = rakeService;
        this.walletBridge = walletBridge;
    }

    /**
     * Start a new hand for the table from its ACTIVE seats: map each seat's money stack to engine chips, build
     * the aggregate game with the table's fixed blinds (in chips) and deal the hand. Returns the live aggregate
     * game (the caller drives actions and then calls {@link #settleHand}).
     */
    @Transactional(readOnly = true)
    public PokerGame startHand(UUID tableId) {
        CashTable table = requireTable(tableId);
        if (!table.isActive()) {
            throw new IllegalStateException("Cash table " + tableId + " is closed");
        }
        CashChipScale scale = CashChipScale.forTable(table);
        List<CashSeat> active = cashSeatRepository.findByCashTableIdAndStatus(tableId, CashSeatStatus.ACTIVE);
        if (active.size() < 2) {
            throw new IllegalStateException("Need at least 2 active seats to start a hand at table " + tableId);
        }
        List<PlayerInfo> infos = new ArrayList<>(active.size());
        for (CashSeat seat : active) {
            infos.add(new PlayerInfo(seat.getPlayerName(), scale.toChips(seat.getStack()), false));
        }
        PokerGame game = PokerGame.create(infos,
                Chips.of(scale.toChips(table.getSmallBlind())),
                Chips.of(scale.toChips(table.getBigBlind())));
        game.startNewHand();
        log.info("Started cash hand {} at table {} with {} players", game.getId(), tableId, active.size());
        return game;
    }

    /**
     * Settle a finished cash hand: take the rake from each awarded pot (no-flop-no-drop — only a contested pot
     * that saw a flop is raked), write the players' final stacks back to their seats in money, and cash out any
     * seat that requested to leave. Idempotent rake recording per (game, pot). Returns the rake taken and the
     * players cashed out.
     */
    @Transactional
    public CashHandResult settleHand(UUID tableId, PokerGame finishedGame) {
        // The hand is complete when its phase reaches FINISHED; isFinished() means the whole match is over
        // (a player busted), which is not the cash-table end condition.
        if (finishedGame.getPhase() != GamePhase.FINISHED) {
            throw new IllegalStateException("Hand " + finishedGame.getId() + " is not finished");
        }
        CashTable table = requireTable(tableId);
        CashChipScale scale = CashChipScale.forTable(table);

        Map<String, CashSeat> seatByName = new HashMap<>();
        for (CashSeat seat : cashSeatRepository.findByCashTableId(tableId)) {
            if (seat.isSeated()) {
                seatByName.put(seat.getPlayerName(), seat);
            }
        }

        // Final stack per played seat, in money (chips -> money at the table scale).
        Map<CashSeat, BigDecimal> stacks = new HashMap<>();
        for (Player player : finishedGame.getPlayers()) {
            CashSeat seat = seatByName.get(player.getName());
            if (seat != null) {
                stacks.put(seat, scale.toMoney(player.getChips()));
            }
        }

        // Rake: only a contested pot (a flop was dealt) is raked; taken from the winner of each awarded pot.
        boolean contested = finishedGame.getCommunityCards().size() >= 3;
        BigDecimal totalRake = BigDecimal.ZERO;
        int potIndex = 0;
        for (DomainEvent event : finishedGame.getDomainEvents()) {
            if (!(event instanceof PotAwarded awarded)) {
                continue;
            }
            BigDecimal potMoney = scale.toMoney(awarded.getAmount().amount());
            String key = "cashrake:" + finishedGame.getId() + ":" + potIndex++;
            BigDecimal rake = rakeService.collectRake(table, potMoney, contested, key);
            if (rake.signum() <= 0) {
                continue;
            }
            Player winner = finishedGame.findPlayerById(awarded.getWinnerId());
            CashSeat winnerSeat = seatByName.get(winner.getName());
            if (winnerSeat != null) {
                stacks.computeIfPresent(winnerSeat, (s, money) -> money.subtract(rake));
            }
            totalRake = totalRake.add(rake);
        }

        stacks.forEach((seat, money) -> {
            seat.setStack(money);
            cashSeatRepository.save(seat);
        });

        // Deferred cash-out: a seat that asked to leave is settled now that the hand has finished.
        List<UUID> cashedOut = new ArrayList<>();
        for (CashSeat seat : stacks.keySet()) {
            if (seat.getStatus() == CashSeatStatus.LEAVING) {
                walletBridge.cashOut(seat.getPlayerId(), tableId);
                cashedOut.add(seat.getPlayerId());
            }
        }

        log.info("Settled cash hand {} at table {}: rake {} {}, {} player(s) cashed out",
                finishedGame.getId(), tableId, totalRake, table.getAsset(), cashedOut.size());
        return new CashHandResult(totalRake, cashedOut);
    }

    private CashTable requireTable(UUID tableId) {
        return cashTableRepository.findById(tableId)
                .orElseThrow(() -> new NoSuchElementException("Cash table not found: " + tableId));
    }

    /** Outcome of settling a cash hand: the rake taken (house revenue) and the players cashed out on leaving. */
    public record CashHandResult(BigDecimal totalRake, List<UUID> cashedOut) {
    }
}
