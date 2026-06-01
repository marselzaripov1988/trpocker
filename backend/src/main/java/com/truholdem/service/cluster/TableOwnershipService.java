package com.truholdem.service.cluster;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
    /** Per-node registry entry: instanceId → peer-reachable base URL (TTL-refreshed by the heartbeat). */
    private static final String NODE_KEY_PREFIX = "truholdem:cluster:node:";
    /** Set of active game tables that need an owner driving their timers (for failover takeover). */
    private static final String ACTIVE_TABLES_KEY = "truholdem:cluster:tables";
    /** Per-table monotonic fencing token: bumped whenever ownership changes hands. */
    private static final String FENCE_KEY_PREFIX = "truholdem:cluster:fence:";

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

    /**
     * Fencing variant of acquire. KEYS[1]=lease, KEYS[2]=fence token; ARGV[1]=instanceId,
     * ARGV[2]=lease TTL ms, ARGV[3]=fence TTL ms. Returns the monotonic fencing token this node now holds
     * (≥1) on success, or -1 if another node owns the lease. The token is bumped (INCR) only when ownership
     * is newly taken (lease free); a renewal by the same owner keeps the current token.
     */
    private static final DefaultRedisScript<Long> ACQUIRE_FENCED_SCRIPT = new DefaultRedisScript<>(
            "local cur = redis.call('GET', KEYS[1]) "
            + "if cur == ARGV[1] then "
            + "  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]) "
            + "  local tok = redis.call('GET', KEYS[2]) "
            + "  if tok == false then tok = redis.call('INCR', KEYS[2]) end "
            + "  redis.call('PEXPIRE', KEYS[2], ARGV[3]) "
            + "  return tonumber(tok) "
            + "elseif cur == false then "
            + "  local tok = redis.call('INCR', KEYS[2]) "
            + "  redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2]) "
            + "  redis.call('PEXPIRE', KEYS[2], ARGV[3]) "
            + "  return tok "
            + "end "
            + "return -1",
            Long.class);

    private final AppProperties appProperties;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final String instanceId;
    private final Set<UUID> ownedLocally = ConcurrentHashMap.newKeySet();
    /** Fencing token this node currently holds per owned table (only populated when fencing is enabled). */
    private final java.util.Map<UUID, Long> heldTokens = new ConcurrentHashMap<>();

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
        if (!appProperties.getCluster().isOwnershipEnabled()) {
            return true; // single-node: this node owns everything
        }
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return degradedOwnership(id, "Redis unavailable"); // cluster mode but no Redis bean
        }
        try {
            if (appProperties.getCluster().isFencingEnabled()) {
                Long token = redis.execute(ACQUIRE_FENCED_SCRIPT, List.of(key(id), fenceKey(id)),
                        instanceId, Long.toString(leaseTtlMillis()), Long.toString(fenceTtlMillis()));
                if (token != null && token >= 1L) {
                    ownedLocally.add(id);
                    heldTokens.put(id, token);
                    return true;
                }
                heldTokens.remove(id);
                return false;
            }
            Long result = redis.execute(ACQUIRE_SCRIPT, List.of(key(id)), instanceId,
                    Long.toString(leaseTtlMillis()));
            boolean acquired = result != null && result == 1L;
            if (acquired) {
                ownedLocally.add(id);
            }
            return acquired;
        } catch (Exception e) {
            log.warn("Ownership acquire failed for {}", id, e);
            return degradedOwnership(id, "Redis error");
        }
    }

    /** True if this node currently owns {@code id}. */
    public boolean isOwner(UUID id) {
        if (id == null) {
            return false;
        }
        if (!appProperties.getCluster().isOwnershipEnabled()) {
            return true; // single-node
        }
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return degradedOwnership(id, "Redis unavailable");
        }
        try {
            return instanceId.equals(redis.opsForValue().get(key(id)));
        } catch (Exception e) {
            log.warn("Ownership check failed for {}", id, e);
            return degradedOwnership(id, "Redis error");
        }
    }

    /**
     * Ownership decision when clustering is on but Redis cannot be consulted. Fail-open (default) assumes
     * ownership so a surviving node stays playable; fail-closed refuses ownership so a partitioned node
     * stops driving timers / claiming tables, preventing two nodes from owning the same table.
     */
    private boolean degradedOwnership(UUID id, String reason) {
        if (appProperties.getCluster().isFailClosed()) {
            log.warn("Refusing ownership of {} ({}) — fail-closed", id, reason);
            return false;
        }
        return true;
    }

    /** The instanceId currently owning {@code id}, or {@code null} if free / disabled / Redis down. */
    public String currentOwner(UUID id) {
        if (id == null) {
            return null;
        }
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return null;
        }
        try {
            return redis.opsForValue().get(key(id));
        } catch (Exception e) {
            log.warn("Ownership lookup failed for {}", id, e);
            return null;
        }
    }

    /** Peer-reachable base URL registered by {@code instanceId}, or {@code null} if unknown. */
    public String baseUrlFor(String instanceId) {
        StringRedisTemplate redis = activeRedis();
        if (redis == null || instanceId == null) {
            return null;
        }
        try {
            return redis.opsForValue().get(NODE_KEY_PREFIX + instanceId);
        } catch (Exception e) {
            log.warn("Node base-url lookup failed for {}", instanceId, e);
            return null;
        }
    }

    /** This node's instanceId (peers route to it via the registry). */
    public String instanceId() {
        return instanceId;
    }

    /** Release ownership of {@code id} (e.g. when the table/tournament is finished). */
    public void release(UUID id) {
        if (id == null) {
            return;
        }
        ownedLocally.remove(id);
        heldTokens.remove(id);
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return;
        }
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key(id)), instanceId);
            redis.opsForSet().remove(ACTIVE_TABLES_KEY, id.toString()); // table is finished → drop from active set
            redis.delete(fenceKey(id)); // table is finished → no more writes to fence
        } catch (Exception e) {
            log.warn("Ownership release failed for {}", id, e);
        }
    }

    /** The fencing token this node currently holds for {@code id}, or {@code null} if it holds none. */
    public Long fenceToken(UUID id) {
        return id == null ? null : heldTokens.get(id);
    }

    /** Full Redis key holding the monotonic fencing token for {@code id} (shared with the hot-state store). */
    public String fenceRedisKey(UUID id) {
        return fenceKey(id);
    }

    /**
     * Record {@code id} as an active game table that needs an owner driving its timers, so a surviving
     * node can take it over if the current owner dies. No-op when clustering is off / Redis is down.
     */
    public void trackActiveTable(UUID id) {
        StringRedisTemplate redis = activeRedis();
        if (redis == null || id == null) {
            return;
        }
        try {
            redis.opsForSet().add(ACTIVE_TABLES_KEY, id.toString());
        } catch (Exception e) {
            log.warn("Active-table tracking failed for {}", id, e);
        }
    }

    /** Drop {@code id} from the active-table set (finished or no longer present). */
    public void untrackActiveTable(UUID id) {
        StringRedisTemplate redis = activeRedis();
        if (redis == null || id == null) {
            return;
        }
        try {
            redis.opsForSet().remove(ACTIVE_TABLES_KEY, id.toString());
        } catch (Exception e) {
            log.warn("Active-table untracking failed for {}", id, e);
        }
    }

    /** All active game tables in the cluster (each may be owned, orphaned, or finished-but-unpruned). */
    public Set<UUID> activeTables() {
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return Set.of();
        }
        try {
            Set<String> members = redis.opsForSet().members(ACTIVE_TABLES_KEY);
            if (members == null || members.isEmpty()) {
                return Set.of();
            }
            Set<UUID> tables = new java.util.HashSet<>(members.size());
            for (String member : members) {
                try {
                    tables.add(UUID.fromString(member));
                } catch (IllegalArgumentException ignored) {
                    redis.opsForSet().remove(ACTIVE_TABLES_KEY, member); // prune a malformed entry
                }
            }
            return tables;
        } catch (Exception e) {
            log.warn("Active-table listing failed", e);
            return Set.of();
        }
    }

    /** Register this node in the cluster registry as soon as it is ready (for peer routing). */
    @EventListener(ApplicationReadyEvent.class)
    public void registerOnStartup() {
        StringRedisTemplate redis = activeRedis();
        if (redis != null) {
            registerSelf(redis);
        }
    }

    /** Keeps this node's registry entry + leases alive; drops entries whose ownership was lost. */
    @Scheduled(fixedDelayString = "#{${app.cluster.lease-ttl-millis:30000} / 3}")
    public void renewOwnedLeases() {
        StringRedisTemplate redis = activeRedis();
        if (redis == null) {
            return;
        }
        registerSelf(redis);
        boolean fencing = appProperties.getCluster().isFencingEnabled();
        for (UUID id : Set.copyOf(ownedLocally)) {
            try {
                if (fencing) {
                    Long token = redis.execute(ACQUIRE_FENCED_SCRIPT, List.of(key(id), fenceKey(id)),
                            instanceId, Long.toString(leaseTtlMillis()), Long.toString(fenceTtlMillis()));
                    if (token != null && token >= 1L) {
                        heldTokens.put(id, token);
                    } else {
                        ownedLocally.remove(id);
                        heldTokens.remove(id);
                    }
                } else {
                    Long result = redis.execute(ACQUIRE_SCRIPT, List.of(key(id)), instanceId,
                            Long.toString(leaseTtlMillis()));
                    if (result == null || result != 1L) {
                        ownedLocally.remove(id);
                    }
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

    /** Refresh this node's registry entry (instanceId → base URL) so peers can route to it. */
    private void registerSelf(StringRedisTemplate redis) {
        String baseUrl = appProperties.getCluster().getNodeBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return; // routing not configured for this node
        }
        try {
            redis.opsForValue().set(NODE_KEY_PREFIX + instanceId, baseUrl, leaseTtlMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Cluster node self-registration failed", e);
        }
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

    /** Fence token lives well beyond the lease so its monotonicity survives owner handover / GC pauses. */
    private long fenceTtlMillis() {
        return leaseTtlMillis() * 20;
    }

    private static String key(UUID id) {
        return KEY_PREFIX + id;
    }

    private static String fenceKey(UUID id) {
        return FENCE_KEY_PREFIX + id;
    }
}
