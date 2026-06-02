package com.truholdem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.truholdem.config.AppProperties;
import com.truholdem.dto.PlayerActionRequest;
import com.truholdem.model.PlayerAction;
import com.truholdem.service.PokerGameService;
import com.truholdem.service.cluster.ClusterActionForwarder;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ClusterInternalController — forwarded action endpoint")
class ClusterInternalControllerTest {

    private static final String SECRET = "s3cret";

    @Mock
    private PokerGameService pokerGameService;

    private ClusterInternalController controller;
    private PlayerActionRequest request;
    private final UUID gameId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getCluster().setSharedSecret(SECRET);
        controller = new ClusterInternalController(pokerGameService, props);
        request = new PlayerActionRequest(UUID.randomUUID().toString(), PlayerAction.CALL, 0);
    }

    @Test
    @DisplayName("rejects a wrong/missing secret with 403")
    void rejectsBadSecret() {
        assertThat(controller.forwardedAction(gameId, "wrong", request).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.forwardedAction(gameId, null, request).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("applies the action and returns 200 on success")
    void appliesOnSuccess() {
        when(pokerGameService.playerActLocal(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(null);
        assertThat(controller.forwardedAction(gameId, SECRET, request).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("translates a game-level rejection (IllegalState) to 409, not 500")
    void translatesConflict() {
        when(pokerGameService.playerActLocal(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("not your turn"));
        assertThat(controller.forwardedAction(gameId, SECRET, request).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("translates a missing game (NoSuchElement) to 404")
    void translatesNotFound() {
        org.mockito.Mockito.doThrow(new NoSuchElementException("gone"))
                .when(pokerGameService)
                .playerActLocal(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(controller.forwardedAction(gameId, SECRET, request).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("translates bad input (IllegalArgument) to 400")
    void translatesBadRequest() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad"))
                .when(pokerGameService)
                .playerActLocal(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(controller.forwardedAction(gameId, SECRET, request).getStatusCode().value()).isEqualTo(400);
    }
}
