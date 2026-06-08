package com.truholdem.service.cluster;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.PlayerActionRequest;
import com.truholdem.model.PlayerAction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Phase 5 cross-node routing: forwards a player action over HTTP to the node that owns the table.
 *
 * <p>The owner processes the action on its own single-writer queue and persists to the shared
 * hot-state, so the originating node only needs the forward to succeed and can then reload the
 * authoritative state. The call carries a shared secret header authenticating the node-to-node hop.
 */
@Component
public class ClusterActionForwarder {

    private static final Logger log = LoggerFactory.getLogger(ClusterActionForwarder.class);

    public static final String SECRET_HEADER = "X-Cluster-Secret";

    private final RestClient restClient;
    private final TableOwnershipService ownership;
    private final AppProperties appProperties;
    private final Counter forwardRequests;
    private final Counter forwardFailures;

    public ClusterActionForwarder(RestClient clusterRestClient, TableOwnershipService ownership,
            AppProperties appProperties, MeterRegistry meterRegistry) {
        this.restClient = clusterRestClient;
        this.ownership = ownership;
        this.appProperties = appProperties;
        this.forwardRequests = Counter.builder("truholdem.cluster.forward.requests")
                .description("Player actions forwarded over HTTP to the owning node (cross-node routing).")
                .register(meterRegistry);
        this.forwardFailures = Counter.builder("truholdem.cluster.forward.failures")
                .description("Cross-node action forwards that failed because the owner was unreachable "
                        + "(unknown address / connect / timeout / 5xx). A non-zero rate breaks multiplayer "
                        + "across nodes — alert on it. (Game-level 4xx rejections are not counted here.)")
                .register(meterRegistry);
    }

    /**
     * Forward an action to {@code ownerInstanceId}. Returns normally on success; throws
     * {@link ClusterForwardException} if the owner's address is unknown or the call fails.
     */
    public void forward(String ownerInstanceId, UUID gameId, UUID commandId, UUID playerId,
            PlayerAction action, int amount) {
        forwardRequests.increment();
        String baseUrl = ownership.baseUrlFor(ownerInstanceId);
        if (baseUrl == null || baseUrl.isBlank()) {
            forwardFailures.increment();
            throw new ClusterForwardException("Unknown address for owner node " + ownerInstanceId
                    + " of game " + gameId);
        }

        PlayerActionRequest body = new PlayerActionRequest(playerId.toString(), action, amount);
        body.setCommandId(commandId == null ? null : commandId.toString());

        String url = baseUrl + "/internal/cluster/game/" + gameId + "/action";
        try {
            restClient.post()
                    .uri(url)
                    .header(SECRET_HEADER, appProperties.getCluster().getSharedSecret())
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Forwarded action for game {} to owner {} ({})", gameId, ownerInstanceId, baseUrl);
        } catch (HttpClientErrorException e) {
            // 4xx: the owner received the action and rejected it at the game level (e.g. not the player's
            // turn). Surface it as a normal game error rather than treating the owner as unreachable — so
            // the caller does NOT re-claim the table. PokerGameController maps IllegalState → 409.
            throw new IllegalStateException("Owner rejected action for game " + gameId
                    + " (" + e.getStatusCode() + ")", e);
        } catch (Exception e) {
            // Connection refused / timeout / 5xx: the owner is unreachable or broken; the caller may
            // re-claim the table once and process locally.
            forwardFailures.increment();
            throw new ClusterForwardException("Failed to forward action for game " + gameId
                    + " to owner " + ownerInstanceId + " (" + url + ")", e);
        }
    }
}
