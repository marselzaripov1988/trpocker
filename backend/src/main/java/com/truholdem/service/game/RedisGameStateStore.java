package com.truholdem.service.game;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.model.Game;

@Component
@ConditionalOnProperty(name = "app.game.hot-state-enabled", havingValue = "true")
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisGameStateStore {

    static final String KEY_PREFIX = "truholdem:game:state:";
    private static final Duration TTL = Duration.ofHours(24);

    private static final Logger logger = LoggerFactory.getLogger(RedisGameStateStore.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisGameStateStore(
            @Qualifier("gameStateRedisTemplate") RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
            redisTemplate.opsForValue().set(key(game.getId()), json, TTL);
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
