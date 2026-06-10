package com.truholdem.dto;

/**
 * Admin request to tune a federation's prize config before payout: the shard-winner qualifier (in ppm of the
 * net pool), the non-champion final-table place shares (comma-separated basis points, index 0 = 2nd place), and
 * the rest-of-final-table bps. Any {@code null} field falls back to the global {@code app.tournament} default.
 */
public record PrizeConfigRequest(
        Integer shardWinnerPpm,
        String finalTablePlaceBps,
        Integer finalTableRestBps) {
}
