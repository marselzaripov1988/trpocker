package com.truholdem.service;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.truholdem.model.Game;
import com.truholdem.model.GamePhase;

/**
 * Produces a viewer-aware JSON projection of a {@link Game} that masks the hole
 * cards of players the viewer is not allowed to see.
 *
 * <p>The mapping is non-mutating: it serializes the (potentially cached, shared)
 * entity into a Jackson tree and edits the copy, so the in-memory/cache state is
 * never modified.
 *
 * <p>A player's hand is revealed when the player id is in {@code visiblePlayerIds}
 * (the viewer's own seats) or when the hand has reached showdown and the player has
 * not folded. Masked hands keep their card count but carry no suit/value, so the
 * client can still render the correct number of face-down cards.
 */
@Component
public class HoleCardSanitizer {

    private final ObjectMapper objectMapper;

    public HoleCardSanitizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param game             the game to project (not modified)
     * @param visiblePlayerIds player ids whose hole cards the viewer may always see
     * @return a sanitized JSON tree safe to send to that viewer
     */
    public JsonNode sanitize(Game game, Set<UUID> visiblePlayerIds) {
        ObjectNode root = objectMapper.valueToTree(game);
        boolean showdown = isShowdown(game);

        JsonNode players = root.get("players");
        if (players == null || !players.isArray()) {
            return root;
        }

        for (JsonNode playerNode : players) {
            if (!playerNode.isObject()) {
                continue;
            }
            maskIfHidden((ObjectNode) playerNode, visiblePlayerIds, showdown);
        }
        return root;
    }

    private void maskIfHidden(ObjectNode playerNode, Set<UUID> visiblePlayerIds, boolean showdown) {
        JsonNode handNode = playerNode.get("hand");
        if (handNode == null || !handNode.isArray()) {
            return;
        }

        UUID playerId = readPlayerId(playerNode);
        boolean folded = playerNode.path("folded").asBoolean(false);
        boolean ownSeat = playerId != null && visiblePlayerIds.contains(playerId);
        boolean revealAtShowdown = showdown && !folded;

        if (ownSeat || revealAtShowdown) {
            return;
        }

        int cardCount = handNode.size();
        ArrayNode masked = objectMapper.createArrayNode();
        for (int i = 0; i < cardCount; i++) {
            ObjectNode hidden = objectMapper.createObjectNode();
            hidden.putNull("suit");
            hidden.putNull("value");
            hidden.put("hidden", true);
            masked.add(hidden);
        }
        playerNode.set("hand", masked);
    }

    private UUID readPlayerId(ObjectNode playerNode) {
        JsonNode idNode = playerNode.get("id");
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        try {
            return UUID.fromString(idNode.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isShowdown(Game game) {
        if (game.isFinished()) {
            return true;
        }
        GamePhase phase = game.getPhase();
        return phase == GamePhase.SHOWDOWN || phase == GamePhase.FINISHED;
    }
}
