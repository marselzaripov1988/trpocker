package com.truholdem.domain.value;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure, dependency-free main-pot / side-pot computation — the single source of truth shared by the legacy
 * {@code PokerGameService} and the {@code domain.aggregate.PokerGame} engine.
 *
 * <p>Splits the pot into layers by all-in level. Two rules that the per-engine implementations previously got
 * wrong, fixed here once:
 * <ul>
 *   <li><b>Dead money:</b> pot amounts are summed over <em>all</em> contributors (including folded players who
 *       put chips in), while eligibility to win a layer is limited to players still in the hand. Previously the
 *       layers summed only non-folded contributions, so a folded player's chips vanished whenever an all-in was
 *       present.</li>
 *   <li><b>Top-pot eligibility:</b> the uncapped top pot (chips above the highest all-in) is contestable only by
 *       players who contributed <em>strictly more</em> than the highest all-in — a player all-in exactly at that
 *       level cannot win the excess. Previously eligibility used {@code >=}, letting an all-in player win chips
 *       above their stake.</li>
 * </ul>
 *
 * <p>Any chips in {@code totalPot} not captured by per-player contributions (e.g. antes) are folded into the main
 * pot, so every chip is always distributed.
 */
public final class PotMath {

    private PotMath() {
    }

    /** One player's total contribution to the pot this hand, plus their fold / all-in status. */
    public record Contribution(UUID playerId, int totalContributed, boolean folded, boolean allIn) {
    }

    /** A pot layer: its chip amount and the ids of players eligible to win it (non-folded). */
    public record Pot(int amount, List<UUID> eligiblePlayerIds) {
    }

    /**
     * Compute the pots. {@code totalPot} is the authoritative chip total (used for the no-all-in case and to
     * reconcile any uncaptured chips into the main pot).
     */
    public static List<Pot> calculate(List<Contribution> contributions, int totalPot) {
        List<UUID> nonFolded = contributions.stream()
                .filter(c -> !c.folded())
                .map(Contribution::playerId)
                .toList();

        List<Integer> levels = contributions.stream()
                .filter(c -> c.allIn() && c.totalContributed() > 0)
                .map(Contribution::totalContributed)
                .distinct()
                .sorted()
                .toList();

        if (levels.isEmpty()) {
            // No all-ins: a single pot of the authoritative total, contested by everyone still in the hand.
            if (totalPot <= 0 || nonFolded.isEmpty()) {
                return List.of();
            }
            return List.of(new Pot(totalPot, nonFolded));
        }

        List<Integer> amounts = new ArrayList<>();
        List<List<UUID>> eligibles = new ArrayList<>();

        int previousLevel = 0;
        for (int level : levels) {
            int amount = 0;
            List<UUID> eligible = new ArrayList<>();
            for (Contribution c : contributions) {
                int layered = Math.min(c.totalContributed(), level) - previousLevel;
                if (layered > 0) {
                    amount += layered;                       // dead money included: all contributors counted
                }
                if (!c.folded() && c.totalContributed() >= level) {
                    eligible.add(c.playerId());              // but only non-folded can win the layer
                }
            }
            if (amount > 0) {
                amounts.add(amount);
                eligibles.add(eligible);
            }
            previousLevel = level;
        }

        // Top (uncapped) pot: chips above the highest all-in, contestable only by those who put in strictly more.
        int maxLevel = levels.get(levels.size() - 1);
        int topAmount = 0;
        List<UUID> topEligible = new ArrayList<>();
        for (Contribution c : contributions) {
            int extra = c.totalContributed() - maxLevel;
            if (extra > 0) {
                topAmount += extra;
                if (!c.folded()) {
                    topEligible.add(c.playerId());
                }
            }
        }
        if (topAmount > 0 && !topEligible.isEmpty()) {
            amounts.add(topAmount);
            eligibles.add(topEligible);
        }

        // Reconcile any chips not captured by per-player contributions (e.g. antes) into the main pot.
        int captured = amounts.stream().mapToInt(Integer::intValue).sum();
        if (totalPot > captured && !amounts.isEmpty()) {
            amounts.set(0, amounts.get(0) + (totalPot - captured));
        }

        List<Pot> pots = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            pots.add(new Pot(amounts.get(i), eligibles.get(i)));
        }
        return pots;
    }
}
