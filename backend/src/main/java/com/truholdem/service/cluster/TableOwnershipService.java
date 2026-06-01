package com.truholdem.service.cluster;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.truholdem.config.AppProperties;

/**
 * Engine-migration Phase 5: per-table ownership for multi-node clustering.
 *
 * <p>Each table ({@code gameId}) or tournament ({@code tournamentId}) is owned by at most one node,
 * recorded as a Redis key {@code truholdem:owner:{uuid}} → owning {@code instanceId} with a TTL lease.
 * A node {@link #acquire}s the lease before scheduling that entity's timers and re-checks {@link #isOwner}
 * when a timer fires, so on a cluster a given timer fires on exactly one node (no double-fire). A
 * heartbeat renews the leases this node holds; if the node dies the lease expires and another node can
 * claim it on the next action for that table.
 *
 * <p>When clustering is disabled ({@code app.cluster.ownership-enabled=false}) or Redis is unavailable,
 * every method reports "owner = true" so single-node behavior is unchanged and gameplay never blocks on
 * Redis.
 */
@Service
public class TableOwnershipService {

    private static final Logger log = LoggerFactory.getLogger(TableOwnershipService.class);

    private static final String KEY_PREFIX = "truholdem:owner:";

    /** Atomically acquire if the key is free or already ours, refreshing the TTL; returns 1 on success. */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) "
            + "if cur == false or cur == ARGV[1] then "
            + "  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]) "
            + "  return 1 "
            + "end "
            + "return 0",
            Long.class);

    /** Delete the key only if we still own it. */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final AppProperties appProperties;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final String instanceId;
    private final Set<UUID> ownedLocally = ConcurrentHashMap.newKeySet();

    public TableOwnershipService(
            AppProperties appProperties,
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${app.websocket.cluster.instance-id:#{T(java.util.UUID).randomUUID().toString()}}")
            String instanceId) {
        this.appProperties = appProperties;
        this.redisTemplateProvider = redisTemplateProvider;
        this.instanceId = instanceId;
    }

    /** Acquire (or renew) ownership of {@code id}. Returns true if this node owns it after the call. */
    public boolean acquire(UUID id) {
        if (id == null) {
            return false;
        }
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return true; // single-node / disabled / Redis down → this node owns everything
        }
        try {
            Long result = redis.execute(ACQUIRE_SCRIPT, List.of(key(id)), instanceId,
                    Long.toString(leaseTtlMillis()));
            boolean acquired = result != null && result == 1L;
            if (acquired) {
                ownedLocally.add(id);
            }
            return acquired;
        } catch (Exception e) {
            log.warn("Ownership acquire failed for {} — treating as owned (degraded)", id, e);
            return true;
        }
    }

    /** True if this node currently owns {@code id}. */
    public boolean isOwner(UUID id) {
        if (id == null) {
            return false;
        }
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return true;
        }
        try {
            return instanceId.equals(redis.opsForValue().get(key(id)));
        } catch (Exception e) {
            log.warn("Ownership check failed for {} — treating as owned (degraded)", id, e);
            return true;
        }
    }

    /** Release ownership of {@code id} (e.g. when the table/tournament is finished). */
    public void release(UUID id) {
        if (id == null) {
            return;
        }
        ownedLocally.remove(id);
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key(id)), instanceId);
        } catch (Exception e) {
            log.warn("Ownership release failed for {}", id, e);
        }
    }

    /** Keeps this node's leases alive; drops entries whose ownership was lost. */
    @Scheduled(fixedDelayString = "#{${app.cluster.lease-ttl-millis:30000} / 3}")
    public void renewOwnedLeases() {
        if (ownedLocally.isEmpty()) {
            return;
        }
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return;
        }
        for (UUID id : Set.copyOf(ownedLocally)) {
            try {
                Long result = redis.execute(ACQUIRE_SCRIPT, List.of(key(id)), instanceId,
                        Long.toString(leaseTtlMillis()));
                if (result == null || result != 1L) {
                    ownedLocally.remove(id);
                }
            } catch (Exception e) {
                log.warn("Ownership renewal failed for {}", id, e);
            }
        }
    }

    /** Test/observability aid: tables this node believes it owns. */
    public Set<UUID> ownedTables() {
        return Collections.unmodifiableSet(ownedLocally);
    }

    /** The active Redis template when ownership is enabled and Redis is present; otherwise null. */
    private StringRedisTemplate activeRedis() {
        if (!appProperties.getCluster().isOwnershipEnabled()) {
            return null;
        }
        return redisTemplateProvider.getIfAvailable();
    }

    private long leaseTtlMillis() {
        return appProperties.getCluster().getLeaseTtlMillis();
    }

    private static String key(UUID id) {
        return KEY_PREFIX + id;
    }
}
