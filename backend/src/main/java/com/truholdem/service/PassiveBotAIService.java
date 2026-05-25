package com.truholdem.service;

import org.springframework.stereotype.Service;

import com.truholdem.model.Game;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.service.AdvancedBotAIService.BotDecision;

/**
 * Minimal bot for load and tournament simulation: no raises, no Monte Carlo.
 */
@Service
public class PassiveBotAIService {

    public BotDecision decide(Game game, Player bot) {
        int toCall = game.getCurrentBet() - bot.getBetAmount();
        if (toCall <= 0) {
            return new BotDecision(PlayerAction.CHECK, 0, "passive: free check");
        }
        if (bot.getChips() >= toCall) {
            return new BotDecision(PlayerAction.CALL, 0, "passive: call to match");
        }
        return new BotDecision(PlayerAction.FOLD, 0, "passive: cannot afford call");
    }
}
