package com.truholdem.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Component
@ConfigurationProperties(prefix = "app")
@Validated
public class AppProperties {

    @Valid
    @NotNull
    private final Jwt jwt = new Jwt();

    @Valid
    @NotNull
    private final Cors cors = new Cors();

    @Valid
    @NotNull
    private final WebSocket websocket = new WebSocket();

    @Valid
    @NotNull
    private final Game game = new Game();

    @Valid
    @NotNull
    private final Tournament tournament = new Tournament();

    
    public Jwt getJwt() {
        return jwt;
    }

    public Cors getCors() {
        return cors;
    }

    public WebSocket getWebsocket() {
        return websocket;
    }

    public Game getGame() {
        return game;
    }

    public Tournament getTournament() {
        return tournament;
    }

    public static class Jwt {
        @NotBlank
        private String secret;

        @Min(1)
        private long expiration;

        @Min(1)
        private long refreshExpiration;

        
        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getExpiration() {
            return expiration;
        }

        public void setExpiration(long expiration) {
            this.expiration = expiration;
        }

        public long getRefreshExpiration() {
            return refreshExpiration;
        }

        public void setRefreshExpiration(long refreshExpiration) {
            this.refreshExpiration = refreshExpiration;
        }
    }

    public static class Cors {
        @NotNull
        private List<String> allowedOrigins;

        @NotNull
        private List<String> allowedMethods;

        @NotNull
        private List<String> allowedHeaders;

        private boolean allowCredentials = true;

        
        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public List<String> getAllowedMethods() {
            return allowedMethods;
        }

        public void setAllowedMethods(List<String> allowedMethods) {
            this.allowedMethods = allowedMethods;
        }

        public List<String> getAllowedHeaders() {
            return allowedHeaders;
        }

        public void setAllowedHeaders(List<String> allowedHeaders) {
            this.allowedHeaders = allowedHeaders;
        }

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }
    }

    public static class WebSocket {
        @NotNull
        private List<String> allowedOrigins;

        
        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Game {
        @Min(1)
        private int defaultChips;

        @Min(1)
        private int defaultSmallBlind;

        @Min(1)
        private int defaultBigBlind;

        @Min(2)
        private int maxPlayers;

        @Min(2)
        private int minPlayers;

        @Min(100)
        private long botThinkTime = 100;

        /** Phase 2: Redis hot state during active play (PostgreSQL on hand milestones). */
        private boolean hotStateEnabled = true;

        /** When hot state is on, skip PostgreSQL write on every player action. */
        private boolean persistOnHandEndOnly = true;

        /** Buffer per-action statistics until hand completion. */
        private boolean bufferStatisticsOnActions = true;

        @Min(50)
        private int botMonteCarloIterations = 500;

        /** Phase 3: enqueue PostgreSQL persist on hand milestones (Redis remains authoritative). */
        private boolean asyncPersistEnabled = false;

        
        public int getDefaultChips() {
            return defaultChips;
        }

        public void setDefaultChips(int defaultChips) {
            this.defaultChips = defaultChips;
        }

        public int getDefaultSmallBlind() {
            return defaultSmallBlind;
        }

        public void setDefaultSmallBlind(int defaultSmallBlind) {
            this.defaultSmallBlind = defaultSmallBlind;
        }

        public int getDefaultBigBlind() {
            return defaultBigBlind;
        }

        public void setDefaultBigBlind(int defaultBigBlind) {
            this.defaultBigBlind = defaultBigBlind;
        }

        public int getMaxPlayers() {
            return maxPlayers;
        }

        public void setMaxPlayers(int maxPlayers) {
            this.maxPlayers = maxPlayers;
        }

        public int getMinPlayers() {
            return minPlayers;
        }

        public void setMinPlayers(int minPlayers) {
            this.minPlayers = minPlayers;
        }

        public long getBotThinkTime() {
            return botThinkTime;
        }

        public void setBotThinkTime(long botThinkTime) {
            this.botThinkTime = botThinkTime;
        }

        public boolean isHotStateEnabled() {
            return hotStateEnabled;
        }

        public void setHotStateEnabled(boolean hotStateEnabled) {
            this.hotStateEnabled = hotStateEnabled;
        }

        public boolean isPersistOnHandEndOnly() {
            return persistOnHandEndOnly;
        }

        public void setPersistOnHandEndOnly(boolean persistOnHandEndOnly) {
            this.persistOnHandEndOnly = persistOnHandEndOnly;
        }

        public boolean isBufferStatisticsOnActions() {
            return bufferStatisticsOnActions;
        }

        public void setBufferStatisticsOnActions(boolean bufferStatisticsOnActions) {
            this.bufferStatisticsOnActions = bufferStatisticsOnActions;
        }

        public int getBotMonteCarloIterations() {
            return botMonteCarloIterations;
        }

        public void setBotMonteCarloIterations(int botMonteCarloIterations) {
            this.botMonteCarloIterations = botMonteCarloIterations;
        }

        public boolean isAsyncPersistEnabled() {
            return asyncPersistEnabled;
        }

        public void setAsyncPersistEnabled(boolean asyncPersistEnabled) {
            this.asyncPersistEnabled = asyncPersistEnabled;
        }
    }

    public static class Tournament {
        /** Hard cap for maxPlayers on create/register. */
        @Min(2)
        private int maxPlayersLimit = 10_000;

        /** Use async table creation when registered count is at or above this value. */
        @Min(2)
        private int asyncStartThreshold = 500;

        /** Batch size for persisting tables during tournament start. */
        @Min(10)
        private int startBatchSize = 200;

        /** Default page size for paginated registration/leaderboard APIs. */
        @Min(1)
        private int defaultPageSize = 50;

        /** Phase 3: logical shards for table-scoped WebSocket topics (tableNumber % shardCount). */
        @Min(1)
        private int shardCount = 16;

        /** When true, table events also go to /topic/tournament/{id}/table/{n}. */
        private boolean tableTopicsEnabled = true;

        /**
         * When &gt; 0, overrides blind-structure level duration for scheduling and API timers (tests).
         */
        @Min(0)
        private int levelDurationSeconds = 0;

        public int getMaxPlayersLimit() {
            return maxPlayersLimit;
        }

        public void setMaxPlayersLimit(int maxPlayersLimit) {
            this.maxPlayersLimit = maxPlayersLimit;
        }

        public int getAsyncStartThreshold() {
            return asyncStartThreshold;
        }

        public void setAsyncStartThreshold(int asyncStartThreshold) {
            this.asyncStartThreshold = asyncStartThreshold;
        }

        public int getStartBatchSize() {
            return startBatchSize;
        }

        public void setStartBatchSize(int startBatchSize) {
            this.startBatchSize = startBatchSize;
        }

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }

        public int getShardCount() {
            return shardCount;
        }

        public void setShardCount(int shardCount) {
            this.shardCount = shardCount;
        }

        public boolean isTableTopicsEnabled() {
            return tableTopicsEnabled;
        }

        public void setTableTopicsEnabled(boolean tableTopicsEnabled) {
            this.tableTopicsEnabled = tableTopicsEnabled;
        }

        public int getLevelDurationSeconds() {
            return levelDurationSeconds;
        }

        public void setLevelDurationSeconds(int levelDurationSeconds) {
            this.levelDurationSeconds = levelDurationSeconds;
        }
    }
}
