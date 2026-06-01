package com.truholdem.service.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.truholdem.config.AppProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TableOwnershipService — Redis-lease per-table ownership")
class TableOwnershipServiceTest {

    private static final String NODE = "node-1";

    @Mock
    private ObjectProvider<StringRedisTemplate> redisProvider;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    private AppProperties appProperties;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        lenient().when(redisProvider.getIfAvailable()).thenReturn(redis);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    private TableOwnershipService service() {
        return new TableOwnershipService(appProperties, redisProvider, NODE);
    }

    @Test
    @DisplayName("ownership disabled (default): every node owns everything, Redis untouched")
    void disabledOwnsEverything() {
        appProperties.getCluster().setOwnershipEnabled(false);
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isTrue();
        assertThat(service.isOwner(id)).isTrue();
        verify(redisProvider, org.mockito.Mockito.never()).getIfAvailable();
    }

    @Test
    @DisplayName("enabled: acquire reflects the Lua result and tracks owned tables")
    void acquireUsesLease() {
        appProperties.getCluster().setOwnershipEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isTrue();
        assertThat(service.ownedTables()).contains(id);
    }

    @Test
    @DisplayName("enabled: acquire returns false when another node holds the lease")
    void acquireFailsWhenOwnedByOther() {
        appProperties.getCluster().setOwnershipEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isFalse();
        assertThat(service.ownedTables()).doesNotContain(id);
    }

    @Test
    @DisplayName("enabled: isOwner reflects the value stored in Redis")
    void isOwnerReflectsRedis() {
        appProperties.getCluster().setOwnershipEnabled(true);
        TableOwnershipService service = service();

        when(valueOps.get(anyString())).thenReturn(NODE);
        assertThat(service.isOwner(id)).isTrue();

        when(valueOps.get(anyString())).thenReturn("node-2");
        assertThat(service.isOwner(id)).isFalse();
    }

    @Test
    @DisplayName("enabled: release runs the compare-and-delete script and drops the table")
    void releaseDeletes() {
        appProperties.getCluster().setOwnershipEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        TableOwnershipService service = service();
        service.acquire(id);

        service.release(id);

        assertThat(service.ownedTables()).doesNotContain(id);
        verify(redis).execute(any(RedisScript.class), anyList(), any()); // release script (single vararg)
    }

    @Test
    @DisplayName("enabled: renewal drops tables whose lease was lost")
    void renewalDropsLostLeases() {
        appProperties.getCluster().setOwnershipEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);
        TableOwnershipService service = service();
        service.acquire(id);
        assertThat(service.ownedTables()).contains(id);

        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        service.renewOwnedLeases();

        assertThat(service.ownedTables()).doesNotContain(id);
    }

    @Test
    @DisplayName("enabled but Redis unavailable: degrades to owner=true")
    void degradesWhenRedisMissing() {
        appProperties.getCluster().setOwnershipEnabled(true);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isTrue();
        assertThat(service.isOwner(id)).isTrue();
    }

    @Test
    @DisplayName("enabled: a Redis error degrades to owner=true (gameplay never blocks on Redis)")
    void degradesOnRedisError() {
        appProperties.getCluster().setOwnershipEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isTrue();
    }

    @Test
    @DisplayName("fail-closed + Redis missing: refuses ownership (no split-brain)")
    void failClosedRefusesWhenRedisMissing() {
        appProperties.getCluster().setOwnershipEnabled(true);
        appProperties.getCluster().setFailClosed(true);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isFalse();
        assertThat(service.isOwner(id)).isFalse();
    }

    @Test
    @DisplayName("fail-closed + Redis error: refuses ownership")
    void failClosedRefusesOnRedisError() {
        appProperties.getCluster().setOwnershipEnabled(true);
        appProperties.getCluster().setFailClosed(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isFalse();
    }

    @Test
    @DisplayName("fail-closed has no effect in single-node mode (ownership disabled → owns everything)")
    void failClosedIgnoredWhenOwnershipDisabled() {
        appProperties.getCluster().setOwnershipEnabled(false);
        appProperties.getCluster().setFailClosed(true);
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isTrue();
        assertThat(service.isOwner(id)).isTrue();
    }

    @Test
    @DisplayName("fencing enabled: acquire returns a token and exposes it via fenceToken")
    void fencingIssuesToken() {
        appProperties.getCluster().setOwnershipEnabled(true);
        appProperties.getCluster().setFencingEnabled(true);
        // fenced script takes 3 ARGV (instanceId, lease ttl, fence ttl) → 5-arg execute
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(7L);
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isTrue();
        assertThat(service.fenceToken(id)).isEqualTo(7L);
        assertThat(service.ownedTables()).contains(id);
    }

    @Test
    @DisplayName("fencing enabled: acquire fails when another node owns (token -1), no token held")
    void fencingAcquireFailsWhenOwnedByOther() {
        appProperties.getCluster().setOwnershipEnabled(true);
        appProperties.getCluster().setFencingEnabled(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(-1L);
        TableOwnershipService service = service();

        assertThat(service.acquire(id)).isFalse();
        assertThat(service.fenceToken(id)).isNull();
    }
}
