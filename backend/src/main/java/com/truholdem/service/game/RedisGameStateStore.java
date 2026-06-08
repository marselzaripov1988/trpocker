package com.truholdem.service.game;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.AppProperties;
import com.truholdem.model.Game;
import com.truholdem.service.cluster.StaleOwnershipException;
import com.truholdem.service.cluster.TableOwnershipService;

@Component
// Gated solely by the explicit feature switch. A previous @ConditionalOnBean(RedisConnectionFactory)
// guard silently disabled hot-state in production: this @Component is evaluated during component scan,
// before Spring Boot's RedisAutoConfiguration registers the connection factory, so the guard never
// matched on a real boot (it only matched in tests that declare RedisConnectionFactory as a user @Bean).
// The redis starter is always on the classpath, so the factory is always auto-configured when needed.
@ConditionalOnProperty(name = "app.game.hot-state-enabled", havingValue = "true")
public class RedisGameStateStore {

    static final String KEY_PREFIX = "truholdem:game:state:";
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * Fenced write: KEYS[1]=fence token key, KEYS[2]=state key; ARGV[1]=our token, ARGV[2]=json,
     * ARGV[3]=ttl ms. Rejects (returns 0) when the table's current fence token is ahead of ours — i.e.
     * another node has taken ownership since we last acquired — otherwise writes the state and returns 1.
     */
    private static final DefaultRedisScript<Long> FENCED_SET_SCRIPT = new DefaultRedisScript<>(
            "local fence = redis.call('GET', KEYS[1]) "
            + "if fence and tonumber(fence) > tonumber(ARGV[1]) then return 0 end "
            + "redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3]) "
            + "return 1",
            Long.class);

    private static final Logger logger = LoggerFactory.getLogger(RedisGameStateStore.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final TableOwnershipService ownership;
    private final AppProperties appProperties;

    public RedisGameStateStore(
            @Qualifier("gameStateRedisTemplate") RedisTemplate<String, String> redisTemplate,
            @Qualifier("gameStateObjectMapper") ObjectMapper objectMapper,
            TableOwnershipService ownership,
            AppProperties appProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ownership = ownership;
        this.appProperties = appProperties;
    }

    public Optional<Game> find(UUID gameId) {
        String json = redisTemplate.opsForValue().get(key(gameId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, Game.class));
        } catch (JsonProcessingException e) {
            logger.warn("Failed to deserialize game {} from Redis, falling back to database", gameId, e);
            return Optional.empty();
        }
    }

    public void save(Game game) {
        if (game.getId() == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(game);
            // Fence the write only when this node actually holds a token for the table (i.e. it owns it and
            // is mutating it). Cache-population writes for tables this node does not own carry no token and
            // take the plain path, so caching after a DB read is never rejected.
            Long token = appProperties.getCluster().isFencingEnabled()
                    ? ownership.fenceToken(game.getId()) : null;
            if (token != null) {
                Long ok = redisTemplate.execute(FENCED_SET_SCRIPT,
                        List.of(ownership.fenceRedisKey(game.getId()), key(game.getId())),
                        token.toString(), json, Long.toString(TTL.toMillis()));
                if (ok == null || ok == 0L) {
                    throw new StaleOwnershipException(game.getId());
                }
            } else {
                redisTemplate.opsForValue().set(key(game.getId()), json, TTL);
            }
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize game {} to Redis", game.getId(), e);
            throw new IllegalStateException("Failed to persist game state to Redis", e);
        }
    }

    public void evict(UUID gameId) {
        redisTemplate.delete(key(gameId));
    }

    private static String key(UUID gameId) {
        return KEY_PREFIX + gameId;
    }
}
