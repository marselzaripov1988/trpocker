package com.truholdem.service.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the federated-pyramid final-table split (non-champion places): explicit per-place shares
 * for 2nd/3rd, the rest split equally, and the non-champion total never exceeding the pool (the caller pays the
 * champion the remainder, so the whole pool sums to 100%).
 */
@DisplayName("FederatedPrizeSplit — non-champion final-table places")
class FederatedPrizeSplitTest {

    private static final List<Integer> PLACES = List.of(300, 100); // 2nd = 3%, 3rd = 1%
    private static final int REST_BPS = 100;                        // 1% split among the rest

    @Test
    @DisplayName("10-seat final table: 2nd 3%, 3rd 1%, 4th–10th split 1%; champion (remainder) ~95%")
    void tenSeatTable() {
        BigDecimal net = new BigDecimal("1000");
        List<BigDecimal> awards = FederatedPrizeSplit.nonChampionAwards(net, 10, PLACES, REST_BPS);

        assertThat(awards).hasSize(9);                  // places 2..10
        assertThat(awards.get(0)).isEqualByComparingTo("30"); // 2nd: 3%
        assertThat(awards.get(1)).isEqualByComparingTo("10"); // 3rd: 1%

        // 4th–10th (7 players) split 1% (= 10) equally → 10/7 ≈ 1.4286 each.
        BigDecimal each = awards.get(2);
        for (int i = 2; i < 9; i++) {
            assertThat(awards.get(i)).isEqualByComparingTo(each);
        }
        assertThat(each).isEqualByComparingTo(new BigDecimal("10").divide(new BigDecimal("7"), 18, RoundingMode.DOWN));

        // The champion (caller-computed remainder) takes the rest — non-champion total < pool, ~5%.
        BigDecimal nonChampion = sum(awards);
        assertThat(nonChampion).isLessThan(net);
        assertThat(net.subtract(nonChampion)).isGreaterThan(new BigDecimal("949")); // champion ~95%
    }

    @Test
    @DisplayName("2-seat final table pays only the runner-up its explicit place share")
    void twoSeatTable() {
        List<BigDecimal> awards = FederatedPrizeSplit.nonChampionAwards(new BigDecimal("160"), 2, PLACES, REST_BPS);
        assertThat(awards).hasSize(1);
        assertThat(awards.get(0)).isEqualByComparingTo("4.8"); // 2nd: 3% of 160
    }

    @Test
    @DisplayName("a single-player final table has no non-champion places")
    void singlePlayer() {
        assertThat(FederatedPrizeSplit.nonChampionAwards(new BigDecimal("500"), 1, PLACES, REST_BPS)).isEmpty();
    }

    @Test
    @DisplayName("a zero/empty pool pays nothing")
    void zeroPool() {
        assertThat(FederatedPrizeSplit.nonChampionAwards(BigDecimal.ZERO, 10, PLACES, REST_BPS))
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("non-champion total never exceeds the pool, across final-table sizes")
    void neverExceedsPool() {
        BigDecimal net = new BigDecimal("7777.77");
        for (int size = 1; size <= 12; size++) {
            assertThat(sum(FederatedPrizeSplit.nonChampionAwards(net, size, PLACES, REST_BPS)))
                    .as("final table of %d", size)
                    .isLessThanOrEqualTo(net);
        }
    }

    private static BigDecimal sum(List<BigDecimal> xs) {
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
