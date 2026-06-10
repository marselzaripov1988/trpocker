package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.dto.HouseRevenueResponse;
import com.truholdem.dto.HouseRevenueResponse.AssetRevenue;
import com.truholdem.model.CryptoAsset;
import com.truholdem.repository.CashRakeEntryRepository;
import com.truholdem.repository.TournamentFeeEntryRepository;

/**
 * Read-only accounting view of accumulated house revenue per asset, aggregating the two revenue ledgers:
 * tournament commission ({@code tournament_fee_entries}) and cash-table rake ({@code cash_rake_entries}).
 *
 * <p>The revenue is <b>not</b> a wallet balance — it is the gap between funds taken in and funds paid out,
 * sitting in the custodial treasury. Keeping it out of any {@link com.truholdem.model.WalletAccount} is
 * deliberate: a house balance would be summed into the solvency monitor's liabilities and double-count.
 * This service simply reports what has accrued, so an operator can reconcile / decide treasury withdrawals.
 */
@Service
public class HouseRevenueService {

    private final TournamentFeeEntryRepository tournamentFees;
    private final CashRakeEntryRepository cashRake;

    public HouseRevenueService(TournamentFeeEntryRepository tournamentFees, CashRakeEntryRepository cashRake) {
        this.tournamentFees = tournamentFees;
        this.cashRake = cashRake;
    }

    /** Per-asset house revenue (only assets that have earned something), split by source. */
    @Transactional(readOnly = true)
    public HouseRevenueResponse summary() {
        List<AssetRevenue> rows = new ArrayList<>();
        for (CryptoAsset asset : CryptoAsset.values()) {
            BigDecimal fees = nz(tournamentFees.totalFeeForAsset(asset));
            BigDecimal rake = nz(cashRake.totalRakeForAsset(asset));
            BigDecimal total = fees.add(rake);
            if (total.signum() > 0) {
                rows.add(new AssetRevenue(asset.name(), fees, rake, total));
            }
        }
        return new HouseRevenueResponse(rows);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
