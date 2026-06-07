package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.truholdem.config.AppProperties;
import com.truholdem.config.GameEngine;
import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.model.PlayerInfo;
import com.truholdem.service.PokerGameService;

/**
 * Characterization / parity net for the engine migration: a deck-independent hand (a scripted sequence of
 * bets/raises that ends with everyone folding out — never reaching a card-dependent showdown) must produce the
 * <b>identical</b> outcome on the legacy engine and the aggregate engine. The engine is flipped via
 * {@code app.game.engine} at runtime (and restored in {@code finally}), so both run in one context, and the
 * scripted actions depend only on the betting/position bookkeeping — not on the shuffled cards — so the final
 * stacks and winner are deterministic and directly comparable.
 *
 * <p>This is the cross-engine oracle the deeper Phase-C engine changes run against. A deterministic-deck seam
 * (to extend it to showdown / side-pot / split outcomes) and the multi-hand / bot scenarios are added as the
 * remaining parity gaps are closed (see {@code AGGREGATE_MIGRATION_PLAN.md}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Engine parity: deck-independent hands resolve identically on legacy and aggregate")
class CrossEnginePokerParityIT {

    @Autowired private PokerGameService poker;
    @Autowired private AppProperties appProperties;

    private record Step(PlayerAction action, int amount) {
    }

    private record Outcome(Map<String, Integer> finalStacks, String winner) {
    }

    /**
     * Run a hand on {@code engine}: apply the scripted actions to whoever is to act, then fold the remaining
     * players out so the hand always resolves to a single winner without a (card-dependent) showdown.
     */
    private Outcome run(GameEngine engine, int seats, List<Step> script) {
        GameEngine previous = appProperties.getGame().getEngine();
        appProperties.getGame().setEngine(engine);
        try {
            List<PlayerInfo> players = new ArrayList<>();
            for (int i = 0; i < seats; i++) {
                players.add(new PlayerInfo("P" + i, 1000, false));
            }
            UUID id = poker.createNewGame(players, 10, 20).getId();

            for (Step step : script) {
                if (!applyToCurrent(id, step.action(), step.amount())) {
                    break;
                }
            }
            // Fold everyone else out → deterministic single winner, no showdown.
            for (int i = 0; i < 30 && applyToCurrent(id, PlayerAction.FOLD, 0); i++) {
                // keep folding the player to act until the hand resolves
            }

            Game game = poker.getGame(id).orElseThrow();
            Map<String, Integer> stacks = new TreeMap<>();
            for (Player p : game.getPlayers()) {
                stacks.put(p.getName(), p.getChips());
            }
            return new Outcome(stacks, game.getWinnerName());
        } finally {
            appProperties.getGame().setEngine(previous);
        }
    }

    /** Apply one action to the player currently to act; returns false when the hand has resolved. */
    private boolean applyToCurrent(UUID id, PlayerAction action, int amount) {
        Player current = poker.getGame(id).orElseThrow().getCurrentPlayer();
        if (current == null) {
            return false;
        }
        try {
            poker.playerAct(id, current.getId(), action, amount);
            return true;
        } catch (RuntimeException resolvedOrIllegal) {
            return false;
        }
    }

    private void assertParity(int seats, List<Step> script) {
        Outcome legacy = run(GameEngine.LEGACY, seats, script);
        Outcome aggregate = run(GameEngine.AGGREGATE, seats, script);

        assertThat(legacy.finalStacks().values().stream().mapToInt(Integer::intValue).sum())
                .as("chips conserved").isEqualTo(seats * 1000);
        assertThat(legacy.winner()).as("a fold-out has a winner").isNotNull();
        assertThat(aggregate.winner()).as("winner matches legacy").isEqualTo(legacy.winner());
        assertThat(aggregate.finalStacks()).as("final stacks match legacy").isEqualTo(legacy.finalStacks());
    }

    @Test
    @DisplayName("3-handed fold-out: stacks + winner identical")
    void threeHandedFoldOut() {
        assertParity(3, List.of());
    }

    @Test
    @DisplayName("heads-up fold: stacks + winner identical")
    void headsUpFold() {
        assertParity(2, List.of());
    }

    @Test
    @DisplayName("raise then everyone folds: stacks + winner identical")
    void raiseThenFold() {
        // The player to act raises to 60; the rest fold to it → the raiser wins the blinds.
        assertParity(3, List.of(new Step(PlayerAction.RAISE, 60)));
    }

    @Test
    @DisplayName("raise, re-raise, fold: stacks + winner identical")
    void raiseReraiseFold() {
        // First raises to 60, next re-raises to 160; the fold-out then resolves it.
        assertParity(3, List.of(new Step(PlayerAction.RAISE, 60), new Step(PlayerAction.RAISE, 160)));
    }
}
