package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.model.CashSeat;
import com.truholdem.model.CashSeatStatus;
import com.truholdem.model.CashTable;
import com.truholdem.repository.CashSeatRepository;
import com.truholdem.repository.CashTableRepository;

/**
 * Bridge between the real-money crypto wallet and cash (ring) tables: sitting down debits the player's
 * {@link com.truholdem.model.WalletAccount} for their chosen buy-in and seats them with that stack, atomically
 * (if the debit fails the seat is rolled back). The buy-in is validated against the table's min/max and an open
 * seat is assigned; a player may hold only one live seat per table. The wallet charge is idempotent on the new
 * seat's id, so a re-sit after standing up is a fresh buy-in while a double-submit of the same seat is rejected.
 * Cash-out (crediting the remaining stack back) is a later slice.
 */
@Service
public class CashGameWalletService {

    private static final Logger log = LoggerFactory.getLogger(CashGameWalletService.class);

    private final WalletService walletService;
    private final CashTableRepository cashTableRepository;
    private final CashSeatRepository cashSeatRepository;

    public CashGameWalletService(WalletService walletService, CashTableRepository cashTableRepository,
            CashSeatRepository cashSeatRepository) {
        this.walletService = walletService;
        this.cashTableRepository = cashTableRepository;
        this.cashSeatRepository = cashSeatRepository;
    }

    /**
     * Sit down at a cash table: validate + seat + debit the wallet, atomically. Returns the new seat.
     *
     * @throws NoSuchElementException   the table does not exist
     * @throws IllegalStateException    the table is closed, full, or the player already holds a live seat
     * @throws IllegalArgumentException the buy-in is outside the table's {@code [minBuyIn, maxBuyIn]} range
     */
    @Transactional
    public CashSeat buyIn(UUID userId, UUID tableId, String playerName, BigDecimal buyIn) {
        CashTable table = cashTableRepository.findById(tableId)
                .orElseThrow(() -> new NoSuchElementException("Cash table not found: " + tableId));
        if (!table.isActive()) {
            throw new IllegalStateException("Cash table " + tableId + " is closed");
        }
        if (buyIn == null || buyIn.compareTo(table.getMinBuyIn()) < 0 || buyIn.compareTo(table.getMaxBuyIn()) > 0) {
            throw new IllegalArgumentException("Buy-in " + buyIn + " is outside the table range ["
                    + table.getMinBuyIn() + ", " + table.getMaxBuyIn() + "]");
        }
        if (cashSeatRepository.findByCashTableIdAndPlayerIdAndStatusNot(tableId, userId, CashSeatStatus.LEFT)
                .isPresent()) {
            throw new IllegalStateException("Player " + userId + " already holds a seat at table " + tableId);
        }

        int seatNumber = firstFreeSeat(table);

        // Persist the seat first so its generated id can key the (idempotent) wallet charge; if the debit fails
        // (insufficient funds / payments disabled) the surrounding transaction rolls the seat back.
        CashSeat seat = cashSeatRepository.save(new CashSeat(tableId, userId, playerName, seatNumber, buyIn));
        walletService.chargeCashBuyIn(userId, table.getAsset(), buyIn, buyInKey(seat.getId()));

        log.info("User {} sat at cash table {} seat {} with {} {}",
                userId, tableId, seatNumber, buyIn, table.getAsset());
        return seat;
    }

    /**
     * Request to stand up: mark the seat {@code LEAVING} so the engine deals the player out and cashes them
     * out once the current hand finishes (see {@link #cashOut}). Between hands this is immediately followed by a
     * cash-out. No-op-safe: returns the seat in its (possibly already-leaving) state.
     *
     * @throws NoSuchElementException the player holds no live seat at the table
     */
    @Transactional
    public CashSeat requestLeave(UUID userId, UUID tableId) {
        CashSeat seat = liveSeat(userId, tableId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Player " + userId + " holds no seat at table " + tableId));
        seat.requestLeave();
        return cashSeatRepository.save(seat);
    }

    /**
     * Stand up and cash out: credit the seat's remaining stack back to the wallet and free the seat. Idempotent
     * — the credit is keyed on the seat id (so a re-run does not double-credit) and a player with no live seat
     * (already cashed out / never seated) is a no-op returning zero. A busted (zero) stack frees the seat
     * without a wallet credit. Returns the amount credited.
     */
    @Transactional
    public BigDecimal cashOut(UUID userId, UUID tableId) {
        CashSeat seat = liveSeat(userId, tableId).orElse(null);
        if (seat == null) {
            return BigDecimal.ZERO;
        }
        CashTable table = cashTableRepository.findById(tableId)
                .orElseThrow(() -> new NoSuchElementException("Cash table not found: " + tableId));
        BigDecimal stack = seat.getStack();
        walletService.creditCashOut(userId, table.getAsset(), stack, cashOutKey(seat.getId()));
        seat.markLeft();
        cashSeatRepository.save(seat);
        log.info("User {} stood up from cash table {} seat {} — cashed out {} {}",
                userId, tableId, seat.getSeatNumber(), stack, table.getAsset());
        return stack;
    }

    private java.util.Optional<CashSeat> liveSeat(UUID userId, UUID tableId) {
        return cashSeatRepository.findByCashTableIdAndPlayerIdAndStatusNot(tableId, userId, CashSeatStatus.LEFT);
    }

    private int firstFreeSeat(CashTable table) {
        Set<Integer> taken = cashSeatRepository.findByCashTableId(table.getId()).stream()
                .filter(CashSeat::isSeated)
                .map(CashSeat::getSeatNumber)
                .collect(Collectors.toSet());
        for (int seat = 0; seat < table.getMaxSeats(); seat++) {
            if (!taken.contains(seat)) {
                return seat;
            }
        }
        throw new IllegalStateException("Cash table " + table.getId() + " is full");
    }

    private static String buyInKey(UUID seatId) {
        return "cashbuyin:" + seatId;
    }

    private static String cashOutKey(UUID seatId) {
        return "cashout:" + seatId;
    }
}
