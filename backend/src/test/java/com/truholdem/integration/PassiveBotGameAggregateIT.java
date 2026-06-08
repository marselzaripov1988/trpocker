package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerInfo;
import com.truholdem.service.PokerGameService;

/**
 * Phase E — pins {@code app.game.engine=aggregate} and runs a full ten-bot hand to completion through the service
 * (`createNewGame` → repeated `executeBotAction` → showdown). The aggregate-engine ITs are mostly heads-up; this
 * guards a <b>many-way</b> pot (ten seats, side pots, multi-player showdown) end-to-end on the aggregate kernel,
 * asserting the hand finishes with a winner and chips are conserved across all ten stacks. Twin of
 * {@code PassiveBotGameIT}, which runs on the default legacy engine.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.game.engine=aggregate",
        "app.game.bot-mode=passive"
})
@Transactional
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Passive bot game integration — aggregate engine")
class PassiveBotGameAggregateIT {

    private static final int TABLE_SIZE = 10;
    private static final int MAX_ACTIONS = 500;

    @Autowired
    private PokerGameService pokerGameService;

    @Test
    @DisplayName("a full ten-bot hand finishes with a winner and conserved chips on the aggregate engine")
    void shouldCompleteManyWayHandOnAggregate() {
        List<PlayerInfo> players = new ArrayList<>();
        for (int i = 1; i <= TABLE_SIZE; i++) {
            players.add(new PlayerInfo("PassiveBot_" + i, 1000, true));
        }

        Game game = pokerGameService.createNewGame(players);
        UUID gameId = game.getId();
        assertThat(game.getPlayers()).hasSize(TABLE_SIZE);

        int actions = 0;
        while (!game.isFinished() && actions < MAX_ACTIONS) {
            game = pokerGameService.getGame(gameId).orElseThrow();
            Player current = game.getCurrentPlayer();
            if (current == null || current.isFolded() || current.isAllIn()) {
                actions++;
                continue;
            }
            assertThat(current.isBot()).isTrue();
            game = pokerGameService.executeBotAction(gameId, current.getId());
            actions++;
        }

        game = pokerGameService.getGame(gameId).orElseThrow();
        assertThat(game.isFinished()).isTrue();
        assertThat(game.getWinnerName()).isNotBlank();
        assertThat(actions).isLessThan(MAX_ACTIONS);
        assertThat(game.getPlayers().stream().mapToInt(Player::getChips).sum())
                .as("chips conserved across the many-way hand").isEqualTo(TABLE_SIZE * 1000);
    }
}
