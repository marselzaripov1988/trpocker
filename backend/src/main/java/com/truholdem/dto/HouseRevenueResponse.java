package com.truholdem.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin read view of accumulated house revenue per asset — the platform's earnings, split by source
 * (tournament commission + cash-table rake). This is an <b>accounting</b> view over the revenue ledgers
 * ({@code tournament_fee_entries} + {@code cash_rake_entries}); the funds themselves sit in the custodial
 * treasury (they are never credited to a wallet account, so they don't inflate user liabilities).
 */
public record HouseRevenueResponse(List<AssetRevenue> assets) {

    /** Accumulated revenue for one asset (major units), broken down by source. */
    public record AssetRevenue(
            String asset,
            BigDecimal tournamentFees,
            BigDecimal cashRake,
            BigDecimal total) {
    }
}
