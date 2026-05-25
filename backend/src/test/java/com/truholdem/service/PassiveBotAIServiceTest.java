package com.truholdem.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.service.AdvancedBotAIService.BotDecision;

@DisplayName("PassiveBotAIService Tests")
class PassiveBotAIServiceTest {

    private final PassiveBotAIService passiveBot = new PassiveBotAIService();
    private Game game;
    private Player bot;

    @BeforeEach
    void setUp() {
        game = new Game();
        game.setId(UUID.randomUUID());
        game.setCurrentBet(20);
        bot = new Player("Bot_1", 1000, true);
        bot.setId(UUID.randomUUID());
        bot.setBetAmount(0);
        game.getPlayers().add(bot);
        game.getPlayers().add(new Player("Bot_2", 1000, true));
    }

    @Test
    @DisplayName("Should check when bet is already matched")
    void shouldCheckWhenNothingToCall() {
        game.setCurrentBet(20);
        bot.setBetAmount(20);

        BotDecision decision = passiveBot.decide(game, bot);

        assertThat(decision.action()).isEqualTo(PlayerAction.CHECK);
        assertThat(decision.amount()).isZero();
    }

    @Test
    @DisplayName("Should call when facing a bet")
    void shouldCallWhenFacingBet() {
        game.setCurrentBet(40);
        bot.setBetAmount(10);

        BotDecision decision = passiveBot.decide(game, bot);

        assertThat(decision.action()).isEqualTo(PlayerAction.CALL);
    }

    @Test
    @DisplayName("Should fold when cannot afford call")
    void shouldFoldWhenCannotAffordCall() {
        game.setCurrentBet(500);
        bot.setBetAmount(0);
        bot.setChips(50);

        BotDecision decision = passiveBot.decide(game, bot);

        assertThat(decision.action()).isEqualTo(PlayerAction.FOLD);
    }
}
