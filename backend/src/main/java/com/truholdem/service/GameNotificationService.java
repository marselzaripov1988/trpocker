package com.truholdem.service;

import com.truholdem.dto.PlayerActionMessageDto;
import com.truholdem.dto.ShowdownResult;
import com.truholdem.dto.WebSocketGameUpdateMessage;
import com.truholdem.model.Game;
import com.truholdem.model.GameUpdateType;
import com.truholdem.model.Player;
import com.truholdem.service.tournament.TournamentTableShardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class GameNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(GameNotificationService.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final TournamentTableShardService tableShardService;

    public GameNotificationService(
            SimpMessagingTemplate messagingTemplate,
            TournamentTableShardService tableShardService) {
        this.messagingTemplate = messagingTemplate;
        this.tableShardService = tableShardService;
    }

    public void broadcastGameUpdate(Game game) {
        if (game == null || game.getId() == null) return;

        WebSocketGameUpdateMessage message = new WebSocketGameUpdateMessage(
            GameUpdateType.GAME_STATE,
            game,
            null,
            "Game state updated"
        );

        publish(game, message);
    }

    public void broadcastPlayerAction(Game game, Player player, String action, int amount) {
        if (game == null || game.getId() == null) return;

        PlayerActionMessageDto actionMessage = new PlayerActionMessageDto(
            player.getId(),
            player.getName(),
            action,
            amount,
            player.getChips(),
            player.getBetAmount()
        );

        WebSocketGameUpdateMessage message = new WebSocketGameUpdateMessage(
            GameUpdateType.PLAYER_ACTION,
            game,
            actionMessage,
            player.getName() + " performed " + action
        );

        publish(game, message);
        logger.debug("Broadcast player action: {} {} {}", player.getName(), action, amount);
    }

    public void broadcastPhaseChange(Game game) {
        if (game == null || game.getId() == null) return;

        WebSocketGameUpdateMessage message = new WebSocketGameUpdateMessage(
            GameUpdateType.PHASE_CHANGE,
            game,
            null,
            "Phase changed to " + game.getPhase()
        );

        publish(game, message);
        logger.info("Broadcast phase change for game {}: {}", game.getId(), game.getPhase());
    }

    public void broadcastShowdown(Game game, ShowdownResult result) {
        if (game == null || game.getId() == null) return;

        WebSocketGameUpdateMessage message = new WebSocketGameUpdateMessage(
            GameUpdateType.SHOWDOWN,
            game,
            result,
            result.getMessage()
        );

        publish(game, message);
        logger.info("Broadcast showdown result: {}", result.getMessage());
    }

    public void broadcastGameEnded(Game game, String winnerName) {
        if (game == null || game.getId() == null) return;

        WebSocketGameUpdateMessage message = new WebSocketGameUpdateMessage(
            GameUpdateType.GAME_ENDED,
            game,
            Map.of("winner", winnerName),
            "Game ended. Winner: " + winnerName
        );

        publish(game, message);
        logger.info("Broadcast game ended: Winner {}", winnerName);
    }

    public void sendToUser(String username, String destination, Object payload) {
        messagingTemplate.convertAndSendToUser(username, destination, payload);
    }

    public void broadcastError(UUID gameId, String errorMessage) {
        if (gameId == null) return;

        String destination = "/topic/game/" + gameId + "/errors";

        Map<String, Object> errorDetails = Map.of(
            "type", "ERROR",
            "message", errorMessage,
            "timestamp", System.currentTimeMillis()
        );

        messagingTemplate.convertAndSend(destination, errorDetails);
        tableShardService.tableTopicForGame(gameId)
                .ifPresent(topic -> messagingTemplate.convertAndSend(topic + "/errors", errorDetails));
    }

    private void publish(Game game, WebSocketGameUpdateMessage message) {
        String gameDestination = "/topic/game/" + game.getId();
        messagingTemplate.convertAndSend(gameDestination, message);
        tableShardService.tableTopicForGame(game.getId())
                .ifPresent(topic -> messagingTemplate.convertAndSend(topic, message));
        tableShardService.shardTopicForGame(game.getId())
                .ifPresent(shard -> messagingTemplate.convertAndSend(shard, message));
        logger.debug("Broadcast game message to {} (+ tournament table topics if applicable)", gameDestination);
    }
}
