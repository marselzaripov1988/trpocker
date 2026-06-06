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
}
