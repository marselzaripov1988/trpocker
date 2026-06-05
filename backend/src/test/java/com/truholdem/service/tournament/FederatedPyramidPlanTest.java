package com.truholdem.service.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FederatedPyramidPlan — sharded decomposition of a very large pyramid")
class FederatedPyramidPlanTest {

    @Test
    @DisplayName("1,000,000 / 10,000 / seats=10 → 100 shards of a 10k pyramid + a 100-finalist final")
    void canonicalMillionPlayerCase() {
        FederatedPyramidPlan plan = new FederatedPyramidPlan(1_000_000, 10_000, 10);

        assertThat(plan.shardCount()).isEqualTo(100);
        assertThat(plan.finalistsCount()).isEqualTo(100);
        // 10000 → 1000 → 100 → 10 → 1 table-levels for seats=10.
        assertThat(plan.shardLevels()).isEqualTo(4);
        // 100 finalists → 10 → 1.
        assertThat(plan.finalLevels()).isEqualTo(2);
        assertThat(plan.shardBracket().seatsAtLevel(1)).isEqualTo(10_000);
        assertThat(plan.finalBracket().seatsAtLevel(1)).isEqualTo(100);
    }

    @Test
    @DisplayName("a non-divisible field rounds the shard count up (ceil)")
    void roundsShardCountUp() {
        FederatedPyramidPlan plan = new FederatedPyramidPlan(10_500, 1_000, 10);
        assertThat(plan.shardCount()).isEqualTo(11); // ceil(10500/1000)
        assertThat(plan.finalistsCount()).isEqualTo(11);
    }

    @Test
    @DisplayName("a small example stays a valid two-stage pyramid")
    void smallExample() {
        FederatedPyramidPlan plan = new FederatedPyramidPlan(1_000, 100, 10);
        assertThat(plan.shardCount()).isEqualTo(10);
        assertThat(plan.shardLevels()).isEqualTo(2);  // 100 → 10 → 1
        assertThat(plan.finalLevels()).isEqualTo(1);  // 10 → 1 (single final table)
    }

    @Test
    @DisplayName("rejects invalid configurations")
    void validation() {
        assertThatThrownBy(() -> new FederatedPyramidPlan(1_000, 100, 1))
                .isInstanceOf(IllegalArgumentException.class); // seatsPerTable < 2
        assertThatThrownBy(() -> new FederatedPyramidPlan(50, 100, 10))
                .isInstanceOf(IllegalArgumentException.class); // startingPlayers < shardSize
        assertThatThrownBy(() -> new FederatedPyramidPlan(1_000, 5, 10))
                .isInstanceOf(IllegalArgumentException.class); // shardSize < seatsPerTable
        // shardCount would be < seatsPerTable → final can't form a pyramid.
        assertThatThrownBy(() -> new FederatedPyramidPlan(30_000, 10_000, 10))
                .isInstanceOf(IllegalArgumentException.class); // 3 shards < seats=10
    }
}
