package com.truholdem.service.cluster;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.truholdem.config.AppProperties;
import com.truholdem.model.PlayerAction;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClusterActionForwarder — HTTP forward to the owner node")
class ClusterActionForwarderTest {

    @Mock
    private TableOwnershipService ownership;

    private AppProperties appProperties;
    private MockRestServiceServer server;
    private ClusterActionForwarder forwarder;

    private final UUID gameId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();
    private final UUID commandId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getCluster().setSharedSecret("s3cret");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        forwarder = new ClusterActionForwarder(builder.build(), ownership, appProperties);
    }

    @Test
    @DisplayName("forwards to the owner's URL with the shared-secret header")
    void forwardsWithSecretHeader() {
        when(ownership.baseUrlFor("node-A")).thenReturn("http://node-a:8080");
        server.expect(requestTo("http://node-a:8080/internal/cluster/game/" + gameId + "/action"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(ClusterActionForwarder.SECRET_HEADER, "s3cret"))
                .andRespond(withSuccess());

        forwarder.forward("node-A", gameId, commandId, playerId, PlayerAction.CALL, 0);

        server.verify();
    }

    @Test
    @DisplayName("throws when the owner's address is unknown")
    void throwsWhenOwnerAddressUnknown() {
        when(ownership.baseUrlFor("node-A")).thenReturn(null);

        assertThatThrownBy(() -> forwarder.forward("node-A", gameId, commandId, playerId, PlayerAction.CALL, 0))
                .isInstanceOf(ClusterForwardException.class);
    }

    @Test
    @DisplayName("treats a 5xx from the owner as unreachable (ClusterForwardException → caller may re-claim)")
    void throwsOnOwnerError() {
        when(ownership.baseUrlFor("node-A")).thenReturn("http://node-a:8080");
        server.expect(requestTo("http://node-a:8080/internal/cluster/game/" + gameId + "/action"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> forwarder.forward("node-A", gameId, commandId, playerId, PlayerAction.CALL, 0))
                .isInstanceOf(ClusterForwardException.class);
    }

    @Test
    @DisplayName("treats a 4xx from the owner as a game-level rejection (IllegalState, not unreachable)")
    void propagatesOwnerConflict() {
        when(ownership.baseUrlFor("node-A")).thenReturn("http://node-a:8080");
        server.expect(requestTo("http://node-a:8080/internal/cluster/game/" + gameId + "/action"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT));

        // 409 = the owner processed and rejected the action — surfaced as IllegalState (→ 409 to the client),
        // NOT ClusterForwardException, so the caller does not re-claim the table.
        assertThatThrownBy(() -> forwarder.forward("node-A", gameId, commandId, playerId, PlayerAction.CALL, 0))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ClusterForwardException.class);
    }
}
