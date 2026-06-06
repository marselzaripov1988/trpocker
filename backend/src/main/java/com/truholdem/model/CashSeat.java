package com.truholdem.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A player's seat session at a real-money cash (ring) table: their current real-money stack (in the
 * {@link CashTable}'s asset major units), seat position and lifecycle status. The seat is the unit that ties a
 * seated player's stack to their wallet — a buy-in debits the wallet and seats them; a cash-out credits the
 * remaining stack back (later slices). One row per (table, player) while seated; a stood-up seat is kept as
 * {@link CashSeatStatus#LEFT} for accounting. Inert unless the cash-game feature is enabled.
 */
@Entity
@Table(name = "cash_seats")
public class CashSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cash_table_id", nullable = false)
    private UUID cashTableId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "player_name", nullable = false, length = 64)
    private String playerName;

    /** Zero-based seat position at the table (0 .. maxSeats-1). */
    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    /** Current real-money stack in the table's asset major units. */
    @Column(nullable = false, precision = 38, scale = 18)
    private BigDecimal stack;

    /** Cumulative amount bought in over this seat session (for win/loss accounting). */
    @Column(name = "buy_in_total", nullable = false, precision = 38, scale = 18)
    private BigDecimal buyInTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CashSeatStatus status = CashSeatStatus.ACTIVE;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Version
    private long version;

    protected CashSeat() {
    }

    public CashSeat(UUID cashTableId, UUID playerId, String playerName, int seatNumber, BigDecimal buyIn) {
        this.cashTableId = cashTableId;
        this.playerId = playerId;
        this.playerName = playerName;
        this.seatNumber = seatNumber;
        this.stack = buyIn;
        this.buyInTotal = buyIn;
        this.status = CashSeatStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    /** Top up the stack with an additional buy-in (e.g. re-buy between hands). */
    public void topUp(BigDecimal amount) {
        this.stack = this.stack.add(amount);
        this.buyInTotal = this.buyInTotal.add(amount);
    }

    /** Set the stack to a new value after a hand (winnings credited / losses debited by the engine). */
    public void setStack(BigDecimal newStack) {
        this.stack = newStack;
    }

    public void sitOut() {
        if (status == CashSeatStatus.ACTIVE) {
            status = CashSeatStatus.SITTING_OUT;
        }
    }

    public void sitIn() {
        if (status == CashSeatStatus.SITTING_OUT) {
            status = CashSeatStatus.ACTIVE;
        }
    }

    /** Request to stand up — the player is dealt out and cashed out once the current hand finishes. */
    public void requestLeave() {
        if (status != CashSeatStatus.LEFT) {
            status = CashSeatStatus.LEAVING;
        }
    }

    /** Finalise standing up: the seat is free and the remaining stack has been (or will be) cashed out. */
    public void markLeft() {
        this.status = CashSeatStatus.LEFT;
        this.leftAt = Instant.now();
    }

    public boolean isSeated() {
        return status != CashSeatStatus.LEFT;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCashTableId() {
        return cashTableId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public BigDecimal getStack() {
        return stack;
    }

    public BigDecimal getBuyInTotal() {
        return buyInTotal;
    }

    public CashSeatStatus getStatus() {
        return status;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public Instant getLeftAt() {
        return leftAt;
    }

    public long getVersion() {
        return version;
    }
}
