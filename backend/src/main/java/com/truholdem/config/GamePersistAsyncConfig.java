package com.truholdem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class GamePersistAsyncConfig {

    @Bean(name = "gamePersistExecutor")
    public Executor gamePersistExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(512);
        executor.setThreadNamePrefix("game-persist-");
        executor.initialize();
        return executor;
    }
}
