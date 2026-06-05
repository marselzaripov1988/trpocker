package com.truholdem.service.tournament;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Computes the fixed-bracket seating for a buy-up pyramid at start. Registered (non-buying) players fill the
 * level-1 floor in registration order, <b>skipping the seats inside closed (bought) sub-pyramids</b>; each
 * buyer is placed directly at the level/seat they bought (entering above the floor). Pure + deterministic so
 * it can be unit-tested independently of the live engine, which consumes the plan to create the tables.
 */
public final class PyramidSeatingPlanner {

    private PyramidSeatingPlanner() {
    }

    /** A pre-start higher-level seat purchase (buyer enters at this level/seat instead of the floor). */
    public record Buyout(UUID playerId, int level, int seatIndex) {
    }

    /** A level-1 (floor) seat assignment. */
    public record FloorSeat(int seatIndex, UUID playerId) {
    }

    /** A buyer placed directly at a higher level. */
    public record Placement(UUID playerId, int level, int seatIndex) {
    }

    /** The full start seating: floor seats for non-buyers + elevated placements for buyers. */
    public record SeatingPlan(List<FloorSeat> floor, List<Placement> elevated) {
    }

    /**
     * @param bracket             the fixed bracket
     * @param registeredNonBuyers floor players in registration order (excludes anyone who bought a seat)
     * @param buyouts             the pre-start buy-outs (non-overlapping; validated at purchase time)
     */
    public static SeatingPlan plan(PyramidBracket bracket, List<UUID> registeredNonBuyers,
            List<Buyout> buyouts) {
        long capacityLong = bracket.tablesAtLevel(1) * bracket.seatsPerTable();
        if (capacityLong > Integer.MAX_VALUE) {
            throw new IllegalStateException("bracket too large to plan");
        }
        int capacity = (int) capacityLong;

        boolean[] closed = new boolean[capacity];
        for (Buyout b : buyouts) {
            int start = (int) bracket.subtreeStart(b.level(), b.seatIndex());
            int end = (int) bracket.subtreeEndExclusive(b.level(), b.seatIndex());
            for (int s = start; s < end; s++) {
                if (closed[s]) {
                    throw new IllegalStateException("overlapping buy-outs in seating plan");
                }
                closed[s] = true;
            }
        }

        List<FloorSeat> floor = new ArrayList<>(registeredNonBuyers.size());
        int seat = 0;
        for (UUID player : registeredNonBuyers) {
            while (seat < capacity && closed[seat]) {
                seat++;
            }
            if (seat >= capacity) {
                throw new IllegalStateException(
                        "not enough open level-1 seats for the registered players (closed sub-trees fill the floor)");
            }
            floor.add(new FloorSeat(seat, player));
            seat++;
        }

        List<Placement> elevated = buyouts.stream()
                .map(b -> new Placement(b.playerId(), b.level(), b.seatIndex()))
                .toList();
        return new SeatingPlan(floor, elevated);
    }
}
