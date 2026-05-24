package com.truholdem.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

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
}
