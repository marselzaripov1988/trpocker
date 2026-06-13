package com.truholdem.domain.value;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.domain.value.PotMath.Contribution;
import com.truholdem.domain.value.PotMath.Pot;

/**
 * Oracle tests for side-pot maths against canonical poker rules. Each scenario has a known-correct payout the
 * code must reproduce. Covers the two bugs the shared {@link PotMath} fixes: folded players' dead money must
 * stay in the pot when an all-in is present, and a player all-in at the top level must not win chips above it.
 */
@DisplayName("PotMath — canonical side-pot scenarios")
class PotMathTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    private static Contribution c(UUID id, int total, boolean folded, boolean allIn) {
        return new Contribution(id, total, folded, allIn);
    }

    private static int totalAwarded(List<Pot> pots) {
        return pots.stream().mapToInt(Pot::amount).sum();
    }

    @Test
    @DisplayName("A — folded dead money stays in the pot when an all-in is present")
    void foldedDeadMoneyWithAllIn() {
        // A bets 100 then folds; B all-in 100; C calls 100. True pot = 300, contested by B and C.
        List<Pot> pots = PotMath.calculate(List.of(
                c(A, 100, true, false),
                c(B, 100, false, true),
                c(C, 100, false, false)), 300);

        assertThat(totalAwarded(pots)).isEqualTo(300);          // no chips vanish (was 200 before the fix)
        assertThat(pots).hasSize(1);
        assertThat(pots.get(0).amount()).isEqualTo(300);
        assertThat(pots.get(0).eligiblePlayerIds()).containsExactlyInAnyOrder(B, C).doesNotContain(A);
    }

    @Test
    @DisplayName("B — folded dead money is included with no all-in")
    void foldedDeadMoneyNoAllIn() {
        List<Pot> pots = PotMath.calculate(List.of(
                c(A, 50, true, false),
                c(B, 100, false, false),
                c(C, 100, false, false)), 250);

        assertThat(pots).hasSize(1);
        assertThat(pots.get(0).amount()).isEqualTo(250);
        assertThat(pots.get(0).eligiblePlayerIds()).containsExactlyInAnyOrder(B, C);
    }

    @Test
    @DisplayName("C — unequal all-ins split into main + side pot")
    void unequalAllIns() {
        // A all-in 50, B all-in 100, C calls 100.
        List<Pot> pots = PotMath.calculate(List.of(
                c(A, 50, false, true),
                c(B, 100, false, true),
                c(C, 100, false, false)), 250);

        assertThat(totalAwarded(pots)).isEqualTo(250);
        assertThat(pots).hasSize(2);
        assertThat(pots.get(0).amount()).isEqualTo(150);                       // 50 × 3
        assertThat(pots.get(0).eligiblePlayerIds()).containsExactlyInAnyOrder(A, B, C);
        assertThat(pots.get(1).amount()).isEqualTo(100);                       // (100−50) × 2
        assertThat(pots.get(1).eligiblePlayerIds()).containsExactlyInAnyOrder(B, C);
    }

    @Test
    @DisplayName("D — top pot excludes the player who is all-in at the top level")
    void topPotExcludesAllInAtTop() {
        // A all-in 100; B and C bet 150 each. Top pot (chips above 100) belongs to B and C only — not A.
        List<Pot> pots = PotMath.calculate(List.of(
                c(A, 100, false, true),
                c(B, 150, false, false),
                c(C, 150, false, false)), 400);

        assertThat(totalAwarded(pots)).isEqualTo(400);
        assertThat(pots).hasSize(2);
        assertThat(pots.get(0).amount()).isEqualTo(300);                       // 100 × 3
        assertThat(pots.get(0).eligiblePlayerIds()).containsExactlyInAnyOrder(A, B, C);
        assertThat(pots.get(1).amount()).isEqualTo(100);                       // 50 × 2 above the all-in
        assertThat(pots.get(1).eligiblePlayerIds()).containsExactlyInAnyOrder(B, C).doesNotContain(A);
    }

    @Test
    @DisplayName("E — folded dead money above an all-in level funds the top pot but is not eligible")
    void foldedDeadMoneyAboveAllIn() {
        // A bets 100 then folds; B all-in 40; C bets 100. Pot = 240.
        List<Pot> pots = PotMath.calculate(List.of(
                c(A, 100, true, false),
                c(B, 40, false, true),
                c(C, 100, false, false)), 240);

        assertThat(totalAwarded(pots)).isEqualTo(240);                          // all chips distributed
        assertThat(pots).hasSize(2);
        assertThat(pots.get(0).amount()).isEqualTo(120);                       // 40 × 3
        assertThat(pots.get(0).eligiblePlayerIds()).containsExactlyInAnyOrder(B, C);
        assertThat(pots.get(1).amount()).isEqualTo(120);                       // (100−40)×2 incl. A's dead money
        assertThat(pots.get(1).eligiblePlayerIds()).containsExactly(C);        // only C — A folded, B all-in at 40
    }
}
