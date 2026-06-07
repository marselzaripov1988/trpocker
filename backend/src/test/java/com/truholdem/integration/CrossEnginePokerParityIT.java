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
 * Characterization / parity net for the engine migration: the same deck-independent hand (everyone folds out)
 * must produce the <b>identical</b> outcome on the legacy engine and the aggregate engine. The engine is flipped
 * via {@code app.game.engine} at runtime (and restored), so both run in one context. A fold-out depends only on
 * the betting/position bookkeeping — not on the shuffled cards — so the final stacks and winner are
 * deterministic and directly comparable. Grows as Phase C closes the remaining parity gaps (multi-hand,
 * showdown, bots).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Engine parity: a fold-out hand resolves identically on legacy and aggregate")
class CrossEnginePokerParityIT {

    @Autowired private PokerGameService poker;
    @Autowired private AppProperties appProperties;

    private record Outcome(Map<String, Integer> finalStacks, String winner) {
    }

    /** Create a 3-handed game and fold the player to act until the hand resolves, on the given engine. */
    private Outcome foldOut(GameEngine engine) {
        GameEngine previous = appProperties.getGame().getEngine();
        appProperties.getGame().setEngine(engine);
        try {
            List<PlayerInfo> players = new ArrayList<>(List.of(
                    new PlayerInfo("Alice", 1000, false),
                    new PlayerInfo("Bob", 1000, false),
                    new PlayerInfo("Carol", 1000, false)));
            UUID id = poker.createNewGame(players, 10, 20).getId();

            for (int i = 0; i < 20; i++) {
                Player current = poker.getGame(id).orElseThrow().getCurrentPlayer();
                if (current == null) {
                    break; // hand resolved
                }
                try {
                    poker.playerAct(id, current.getId(), PlayerAction.FOLD, 0);
                } catch (RuntimeException alreadyResolved) {
                    break;
                }
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

    @Test
    @DisplayName("final stacks and the winner are the same on both engines")
    void foldOutMatchesAcrossEngines() {
        Outcome legacy = foldOut(GameEngine.LEGACY);
        Outcome aggregate = foldOut(GameEngine.AGGREGATE);

        assertThat(legacy.finalStacks().values().stream().mapToInt(Integer::intValue).sum())
                .as("chips conserved (3 × 1000)").isEqualTo(3000);
        assertThat(legacy.winner()).as("a fold-out has a winner").isNotNull();
        assertThat(aggregate.winner()).as("winner matches legacy").isEqualTo(legacy.winner());
        assertThat(aggregate.finalStacks()).as("final stacks match legacy").isEqualTo(legacy.finalStacks());
    }
}
