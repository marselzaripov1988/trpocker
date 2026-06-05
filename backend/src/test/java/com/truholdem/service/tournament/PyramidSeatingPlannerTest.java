package com.truholdem.service.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.service.tournament.PyramidSeatingPlanner.Buyout;
import com.truholdem.service.tournament.PyramidSeatingPlanner.SeatingPlan;

@DisplayName("PyramidSeatingPlanner (fixed-bracket start seating; closed sub-trees skipped)")
class PyramidSeatingPlannerTest {

    private final PyramidBracket bracket = new PyramidBracket(1000, 10);

    private List<UUID> players(int n) {
        List<UUID> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new UUID(0, i + 1)); // deterministic ids
        }
        return list;
    }

    @Test
    @DisplayName("no buy-outs: floor fills seats 0..n-1 in order, no elevated")
    void noBuyouts() {
        List<UUID> reg = players(30);
        SeatingPlan plan = PyramidSeatingPlanner.plan(bracket, reg, List.of());

        assertThat(plan.elevated()).isEmpty();
        assertThat(plan.floor()).hasSize(30);
        assertThat(plan.floor().get(0).seatIndex()).isZero();
        assertThat(plan.floor().get(29).seatIndex()).isEqualTo(29);
        assertThat(plan.floor().get(5).playerId()).isEqualTo(reg.get(5));
    }

    @Test
    @DisplayName("a level-2 buy-out at seat 0 closes level-1 seats [0,10); floor skips them")
    void buyoutSkipsClosedSubtree() {
        UUID buyer = new UUID(7, 7);
        List<UUID> reg = players(30);
        SeatingPlan plan = PyramidSeatingPlanner.plan(bracket, reg,
                List.of(new Buyout(buyer, 2, 0)));

        // floor players start at seat 10 (0..9 are closed by the L2#0 buy-out).
        assertThat(plan.floor().get(0).seatIndex()).isEqualTo(10);
        assertThat(plan.floor().get(29).seatIndex()).isEqualTo(39);
        assertThat(plan.floor()).noneMatch(s -> s.seatIndex() < 10);
        // the buyer is elevated, not on the floor.
        assertThat(plan.elevated()).containsExactly(
                new PyramidSeatingPlanner.Placement(buyer, 2, 0));
        assertThat(plan.floor()).noneMatch(s -> s.playerId().equals(buyer));
    }

    @Test
    @DisplayName("multiple buy-outs each close their own sub-tree")
    void multipleBuyouts() {
        SeatingPlan plan = PyramidSeatingPlanner.plan(bracket, players(5),
                List.of(new Buyout(new UUID(1, 1), 2, 0),   // closes [0,10)
                        new Buyout(new UUID(2, 2), 2, 1)));  // closes [10,20)
        assertThat(plan.floor().get(0).seatIndex()).isEqualTo(20); // first open seat after both closed tables
        assertThat(plan.elevated()).hasSize(2);
    }

    @Test
    @DisplayName("over-capacity: more registered players than open floor seats is rejected")
    void overCapacity() {
        PyramidBracket small = new PyramidBracket(100, 10); // 100 level-1 seats
        // Buy L2#0 closes 10 seats → 90 open; 91 registered cannot be seated.
        assertThatThrownBy(() -> PyramidSeatingPlanner.plan(small, players(91),
                List.of(new Buyout(new UUID(9, 9), 2, 0))))
                .isInstanceOf(IllegalStateException.class);
    }
}
