package com.truholdem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class GamePersistAsyncConfig {

    /**
     * Returned as the concrete {@link ThreadPoolTaskExecutor} (not the {@code Executor} interface) so Spring Boot's
     * executor metrics auto-configuration instruments it — exposing {@code executor_queued_tasks},
     * {@code executor_active_threads}, etc. tagged {@code name="gamePersistExecutor"} for backlog alerting.
     */
    @Bean(name = "gamePersistExecutor")
    public ThreadPoolTaskExecutor gamePersistExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(512);
        executor.setThreadNamePrefix("game-persist-");
        executor.initialize();
        return executor;
    }
}
