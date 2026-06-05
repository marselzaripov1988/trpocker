package com.truholdem.service.tournament;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed pyramid bracket for a "buy-up" pyramid tournament: a tree of tables where every seat at level L is
 * fed by one table at level L-1. Level 1 is the bottom (where all players enter); each higher level has
 * {@code 1/seatsPerTable} as many tables, up to the final single-table level. Pure + deterministic.
 *
 * <p>"Buying a seat at level L" gives a guaranteed seat there, skipping (closing the fight of) the whole
 * sub-pyramid below it — {@code seatsPerTable^(L-1)} level-1 seats. The price is what those level-1 buy-ins
 * would have totalled: {@code seatsPerTable^(L-1) * buyIn}. A level-L seat may only be bought while its
 * sub-pyramid is empty (no registered players below it); the number of buyable level-L seats equals the
 * number of clean feeder tables at level L-1.
 */
public final class PyramidBracket {

    private final int seatsPerTable;
    /** tablesPerLevel.get(L-1) = number of tables at level L (1-based levels). */
    private final List<Long> tablesPerLevel;

    public PyramidBracket(long startingPlayers, int seatsPerTable) {
        if (seatsPerTable < 2) {
            throw new IllegalArgumentException("seatsPerTable must be >= 2");
        }
        if (startingPlayers < seatsPerTable) {
            throw new IllegalArgumentException("startingPlayers must be >= seatsPerTable");
        }
        this.seatsPerTable = seatsPerTable;
        this.tablesPerLevel = new ArrayList<>();
        long tables = ceilDiv(startingPlayers, seatsPerTable);
        tablesPerLevel.add(tables);
        while (tables > 1) {
            tables = ceilDiv(tables, seatsPerTable);
            tablesPerLevel.add(tables);
        }
    }

    /** Total number of levels (level 1 = bottom, the last level has a single table). */
    public int levels() {
        return tablesPerLevel.size();
    }

    public int seatsPerTable() {
        return seatsPerTable;
    }

    /** Number of tables at {@code level} (1-based). */
    public long tablesAtLevel(int level) {
        requireLevel(level, 1);
        return tablesPerLevel.get(level - 1);
    }

    /** Number of seats at {@code level} = tables × seatsPerTable. */
    public long seatsAtLevel(int level) {
        return tablesAtLevel(level) * seatsPerTable;
    }

    /** Level-1 seats under one level-{@code buyLevel} seat = {@code seatsPerTable^(buyLevel-1)}. */
    public long subtreeLevelOneSeats(int buyLevel) {
        requireLevel(buyLevel, 2);
        return pow(seatsPerTable, buyLevel - 1);
    }

    /** Buy-out price for a level-{@code buyLevel} seat = its sub-pyramid buy-ins ({@code subtree × buyIn}). */
    public BigDecimal buyoutPrice(int buyLevel, BigDecimal buyIn) {
        return buyIn.multiply(BigDecimal.valueOf(subtreeLevelOneSeats(buyLevel)));
    }

    /** How many level-{@code buyLevel} seats exist to buy = the feeder tables at level {@code buyLevel-1}. */
    public long buyableSeatsAtLevel(int buyLevel) {
        requireLevel(buyLevel, 2);
        return tablesAtLevel(buyLevel - 1);
    }

    /** First level-1 seat index covered by the sub-pyramid under level-{@code buyLevel} seat {@code seatIndex}. */
    public long subtreeStart(int buyLevel, int seatIndex) {
        if (seatIndex < 0 || seatIndex >= buyableSeatsAtLevel(buyLevel)) {
            throw new IllegalArgumentException("seatIndex out of range for level " + buyLevel + ": " + seatIndex);
        }
        return (long) seatIndex * subtreeLevelOneSeats(buyLevel);
    }

    /** Exclusive end of the level-1 seat range covered by that seat's sub-pyramid. */
    public long subtreeEndExclusive(int buyLevel, int seatIndex) {
        return subtreeStart(buyLevel, seatIndex) + subtreeLevelOneSeats(buyLevel);
    }

    /** True if two bought seats' sub-pyramids overlap in level-1 seat space (one contains/intersects the other). */
    public boolean overlaps(int levelA, int seatA, int levelB, int seatB) {
        long aStart = subtreeStart(levelA, seatA);
        long aEnd = subtreeEndExclusive(levelA, seatA);
        long bStart = subtreeStart(levelB, seatB);
        long bEnd = subtreeEndExclusive(levelB, seatB);
        return aStart < bEnd && bStart < aEnd;
    }

    private void requireLevel(int level, int min) {
        if (level < min || level > levels()) {
            throw new IllegalArgumentException(
                    "level must be in [" + min + ", " + levels() + "], got " + level);
        }
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    private static long pow(int base, int exp) {
        long r = 1;
        for (int i = 0; i < exp; i++) {
            r *= base;
        }
        return r;
    }
}
