package com.truholdem.service.tournament;

/**
 * The decomposition of a very-large "federated" pyramid into independent shards plus a final, all reusing the
 * existing {@link PyramidBracket} math. A field of {@code startingPlayers} is split into
 * {@code ceil(startingPlayers / shardSize)} shards of up to {@code shardSize} players; each shard is an
 * ordinary pyramid run down to a single winner, and the shard winners then meet in a final pyramid.
 *
 * <p>Example (the canonical 1,000,000 / 10,000 / seats=10 case): 100 shards, each a 10,000-player pyramid
 * (10000&rarr;1000&rarr;100&rarr;10&rarr;1), producing 100 finalists who play the final pyramid
 * (100&rarr;10&rarr;1). Pure + deterministic — no engine, scheduling or persistence here.
 */
public final class FederatedPyramidPlan {

    private final long startingPlayers;
    private final int shardSize;
    private final int seatsPerTable;
    private final int shardCount;
    private final PyramidBracket shardBracket;
    private final PyramidBracket finalBracket;

    public FederatedPyramidPlan(long startingPlayers, int shardSize, int seatsPerTable) {
        if (seatsPerTable < 2) {
            throw new IllegalArgumentException("seatsPerTable must be >= 2");
        }
        if (shardSize < seatsPerTable) {
            throw new IllegalArgumentException("shardSize must be >= seatsPerTable");
        }
        if (startingPlayers < shardSize) {
            throw new IllegalArgumentException("startingPlayers must be >= shardSize");
        }
        this.startingPlayers = startingPlayers;
        this.shardSize = shardSize;
        this.seatsPerTable = seatsPerTable;
        this.shardCount = (int) ceilDiv(startingPlayers, shardSize);
        if (shardCount < seatsPerTable) {
            throw new IllegalArgumentException(
                    "shardCount (" + shardCount + ") must be >= seatsPerTable so the final is a valid pyramid; "
                            + "use a smaller shardSize or more players");
        }
        this.shardBracket = new PyramidBracket(shardSize, seatsPerTable);
        this.finalBracket = new PyramidBracket(shardCount, seatsPerTable);
    }

    public long startingPlayers() {
        return startingPlayers;
    }

    public int shardSize() {
        return shardSize;
    }

    public int seatsPerTable() {
        return seatsPerTable;
    }

    /** Number of shards = {@code ceil(startingPlayers / shardSize)}. */
    public int shardCount() {
        return shardCount;
    }

    /** Finalists feeding the final = one winner per shard = {@link #shardCount()}. */
    public int finalistsCount() {
        return shardCount;
    }

    /** Pyramid levels inside one shard (e.g. 4 table-levels for 10000 players at seats=10). */
    public int shardLevels() {
        return shardBracket.levels();
    }

    /** Pyramid levels in the final among the shard winners (e.g. 2 for 100 finalists at seats=10). */
    public int finalLevels() {
        return finalBracket.levels();
    }

    /** The (reusable) bracket describing a single shard's pyramid. */
    public PyramidBracket shardBracket() {
        return shardBracket;
    }

    /** The (reusable) bracket describing the final pyramid among the shard winners. */
    public PyramidBracket finalBracket() {
        return finalBracket;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
