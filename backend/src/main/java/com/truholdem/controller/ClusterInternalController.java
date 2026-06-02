package com.truholdem.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.PlayerActionRequest;
import com.truholdem.service.PokerGameService;
import com.truholdem.service.cluster.ClusterActionForwarder;
import com.truholdem.service.game.TableCommandDispatcher;

/**
 * Phase 5: internal node-to-node endpoint. Receives an action forwarded by a non-owner node and
 * processes it LOCALLY (no re-routing — loop prevention) on this owner node's single-writer queue.
 *
 * <p>Authenticated by a shared cluster secret header, not a user JWT (calls are node-to-node). The
 * endpoint rejects everything unless routing is configured with a secret, so it is never an open
 * mutation hole.
 */
@RestController
@RequestMapping("/internal/cluster")
public class ClusterInternalController {

    private final PokerGameService pokerGameService;
    private final AppProperties appProperties;

    public ClusterInternalController(PokerGameService pokerGameService, AppProperties appProperties) {
        this.pokerGameService = pokerGameService;
        this.appProperties = appProperties;
    }

    @PostMapping("/game/{gameId}/action")
    public ResponseEntity<Void> forwardedAction(
            @PathVariable UUID gameId,
            @RequestHeader(value = ClusterActionForwarder.SECRET_HEADER, required = false) String secret,
            @RequestBody PlayerActionRequest request) {
        if (!secretValid(secret)) {
            return ResponseEntity.status(403).build();
        }
        try {
            pokerGameService.playerActLocal(
                    gameId,
                    TableCommandDispatcher.parseCommandId(request.getCommandId()),
                    UUID.fromString(request.getPlayerId()),
                    request.getAction(),
                    request.getAmount());
            return ResponseEntity.ok().build();
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(404).build();
        } catch (IllegalStateException e) {
            // Game-level rejection (e.g. not the player's turn) — a normal 409, not a server fault, so the
            // forwarding node does not mistake it for an unreachable owner and re-claim the table.
            return ResponseEntity.status(409).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).build();
        }
    }

    /** Constant-time secret comparison; rejects when routing is unconfigured (blank secret). */
    private boolean secretValid(String provided) {
        String expected = appProperties.getCluster().getSharedSecret();
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
