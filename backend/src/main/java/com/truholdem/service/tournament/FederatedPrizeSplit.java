package com.truholdem.service.tournament;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure prize-split math for the <b>final table</b> of a federated pyramid — the non-champion places only. Each
 * place 2..k gets an explicit share of the net pool ({@code placeBps}, index 0 = 2nd place); the remaining
 * final-table players (places past the explicit list) split {@code restBps} equally. The grand champion (place
 * 1) is intentionally <b>not</b> computed here: the caller pays the champion the remainder of the pool (net less
 * the shard-winner qualifiers and these non-champion awards), so the whole pool always sums to 100%.
 *
 * <p>Default schedule {@code placeBps=[200,100]}, {@code restBps=100} on a 10-seat final table pays 2% to the
 * runner-up, 1% to third, and 1% split equally among 4th–10th — the champion then takes whatever is left.
 */
final class FederatedPrizeSplit {

    private static final BigDecimal TEN_K = BigDecimal.valueOf(10_000);
    private static final int CRYPTO_SCALE = 18;

    private FederatedPrizeSplit() {
    }

    /**
     * Awards for the non-champion final-table places, indexed by offset from 2nd place (index 0 = 2nd, 1 = 3rd,
     * …) — length {@code max(0, finalTableSize - 1)}. Returns all-zero when there is nothing to pay or no
     * non-champion seat.
     */
    static List<BigDecimal> nonChampionAwards(BigDecimal net, int finalTableSize,
            List<Integer> placeBps, int restBps) {
        int nonChampion = Math.max(0, finalTableSize - 1);
        List<BigDecimal> awards = new ArrayList<>(nonChampion);
        for (int i = 0; i < nonChampion; i++) {
            awards.add(BigDecimal.ZERO);
        }
        if (nonChampion == 0 || net.signum() <= 0) {
            return awards;
        }
        int restCount = Math.max(0, nonChampion - placeBps.size());
        for (int i = 0; i < nonChampion; i++) {
            BigDecimal share;
            if (i < placeBps.size()) {
                share = net.multiply(BigDecimal.valueOf(placeBps.get(i)))
                        .divide(TEN_K, CRYPTO_SCALE, RoundingMode.DOWN);
            } else if (restCount > 0) {
                share = net.multiply(BigDecimal.valueOf(restBps))
                        .divide(TEN_K.multiply(BigDecimal.valueOf(restCount)), CRYPTO_SCALE, RoundingMode.DOWN);
            } else {
                share = BigDecimal.ZERO;
            }
            awards.set(i, share);
        }
        return awards;
    }
}
