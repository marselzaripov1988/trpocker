package com.truholdem.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.model.Card;
import com.truholdem.model.Game;

@Configuration
@ConditionalOnProperty(name = "app.game.hot-state-enabled", havingValue = "true")
@ConditionalOnBean(RedisConnectionFactory.class)
public class GameStateRedisConfig {

    private static final Logger logger = LoggerFactory.getLogger(GameStateRedisConfig.class);

    @Bean(name = "gameStateRedisTemplate")
    public RedisTemplate<String, String> gameStateRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        logger.info("Game state Redis template configured");
        return template;
    }

    /**
     * Dedicated {@link ObjectMapper} for the hot-state Redis store. Redis is the authoritative live state between
     * database writes, so it must serialize the <b>full</b> game — including {@code Game.deck}, which is
     * {@link JsonIgnore}d on the REST/default mapper to keep undealt cards out of API responses. Without this, a
     * cache hit returns a deckless game and the next street cannot be dealt. Only the deck is re-exposed (a
     * targeted mix-in), so client-facing responses still hide it and bidirectional {@code @JsonIgnore}
     * back-references are left untouched (avoiding serialization cycles).
     */
    @Bean(name = "gameStateObjectMapper")
    public ObjectMapper gameStateObjectMapper(ObjectMapper base) {
        ObjectMapper mapper = base.copy();
        mapper.addMixIn(Game.class, GameHotStateMixin.class);
        logger.info("Hot-state Redis ObjectMapper configured (deck + version re-exposed for full-state persistence)");
        return mapper;
    }

    /**
     * Jackson mix-in (never instantiated — only its annotations are read) that re-exposes, for hot-state
     * serialization only, two fields the REST mapper hides: {@code Game.deck} (so a cache hit can still deal the
     * next street) and {@code Game.version} (the JPA optimistic-lock token — without it a Redis-reloaded game has a
     * null version and a later persist is mishandled as a transient insert). The REST mapper still hides both.
     */
    public static final class GameHotStateMixin {
        @JsonIgnore(false)
        @JsonProperty("deck")
        List<Card> deck;

        @JsonIgnore(false)
        @JsonProperty("version")
        Long version;

        private GameHotStateMixin() {
        }
    }
}
