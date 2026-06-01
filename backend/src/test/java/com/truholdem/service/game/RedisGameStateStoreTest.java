package com.truholdem.service.game;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.service.cluster.StaleOwnershipException;
import com.truholdem.service.cluster.TableOwnershipService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RedisGameStateStore — fenced hot-state write")
class RedisGameStateStoreTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private TableOwnershipService ownership;

    private AppProperties appProperties;
    private Game game;
    private RedisGameStateStore store;

    @BeforeEach
    void setUp() throws Exception {
        appProperties = new AppProperties();
        game = new Game();
        game.setId(UUID.randomUUID());
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(ownership.fenceRedisKey(any())).thenReturn("truholdem:cluster:fence:x");
        store = new RedisGameStateStore(redisTemplate, objectMapper, ownership, appProperties);
    }

    @Test
    @DisplayName("fencing disabled: plain SET, no fenced script")
    void plainWriteWhenFencingDisabled() {
        store.save(game);

        verify(valueOps).set(anyString(), eq("{}"), any(Duration.class));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("fencing on but no held token (not the owner): plain SET")
    void plainWriteWhenNoToken() {
        appProperties.getCluster().setFencingEnabled(true);
        when(ownership.fenceToken(any())).thenReturn(null);

        store.save(game);

        verify(valueOps).set(anyString(), eq("{}"), any(Duration.class));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("fencing on + current token: fenced SET succeeds")
    void fencedWriteSucceeds() {
        appProperties.getCluster().setFencingEnabled(true);
        when(ownership.fenceToken(any())).thenReturn(3L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);

        assertThatCode(() -> store.save(game)).doesNotThrowAnyException();
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(), any(), any());
    }

    @Test
    @DisplayName("fencing on + stale token (a newer owner exists): write rejected → StaleOwnershipException")
    void fencedWriteRejectedThrows() {
        appProperties.getCluster().setFencingEnabled(true);
        when(ownership.fenceToken(any())).thenReturn(3L);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(0L);

        assertThatThrownBy(() -> store.save(game)).isInstanceOf(StaleOwnershipException.class);
    }
}
