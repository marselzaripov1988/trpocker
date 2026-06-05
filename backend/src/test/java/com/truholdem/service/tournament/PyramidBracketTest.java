package com.truholdem.service.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PyramidBracket (buy-up tree structure + sub-tree pricing)")
class PyramidBracketTest {

    /** The example from the spec: 1000 players, 10 seats/table. */
    private final PyramidBracket bracket = new PyramidBracket(1000, 10);

    @Test
    @DisplayName("tables per level: 1000 → 100 → 10 → 1")
    void tableCounts() {
        assertThat(bracket.levels()).isEqualTo(3);
        assertThat(bracket.tablesAtLevel(1)).isEqualTo(100);
        assertThat(bracket.tablesAtLevel(2)).isEqualTo(10);
        assertThat(bracket.tablesAtLevel(3)).isEqualTo(1);
        assertThat(bracket.seatsAtLevel(2)).isEqualTo(100); // 10 tables × 10 seats
    }

    @Test
    @DisplayName("buyable level-2 seats = level-1 feeder tables (100); level-3 = 10")
    void buyableSeats() {
        assertThat(bracket.buyableSeatsAtLevel(2)).isEqualTo(100);
        assertThat(bracket.buyableSeatsAtLevel(3)).isEqualTo(10);
    }

    @Test
    @DisplayName("sub-tree level-1 seats grow by seatsPerTable per level")
    void subtreeSeats() {
        assertThat(bracket.subtreeLevelOneSeats(2)).isEqualTo(10);  // one level-1 table
        assertThat(bracket.subtreeLevelOneSeats(3)).isEqualTo(100); // ten level-1 tables
    }

    @Test
    @DisplayName("buy-out price = sub-tree buy-ins (seatsPerTable^(L-1) × buyIn)")
    void pricing() {
        BigDecimal buyIn = new BigDecimal("5");
        assertThat(bracket.buyoutPrice(2, buyIn)).isEqualByComparingTo("50");   // 10 × 5
        assertThat(bracket.buyoutPrice(3, buyIn)).isEqualByComparingTo("500");  // 100 × 5
    }

    @Test
    @DisplayName("level 1 cannot be bought; out-of-range levels rejected")
    void levelGuards() {
        assertThatThrownBy(() -> bracket.buyoutPrice(1, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bracket.subtreeLevelOneSeats(4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a small bracket (100 players, 10 seats) has two levels")
    void smallBracket() {
        PyramidBracket small = new PyramidBracket(100, 10);
        assertThat(small.levels()).isEqualTo(2);
        assertThat(small.tablesAtLevel(1)).isEqualTo(10);
        assertThat(small.tablesAtLevel(2)).isEqualTo(1);
        assertThat(small.buyableSeatsAtLevel(2)).isEqualTo(10);
    }
}
