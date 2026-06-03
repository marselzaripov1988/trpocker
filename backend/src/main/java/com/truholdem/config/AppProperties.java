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

    @Valid
    @NotNull
    private final Cluster cluster = new Cluster();

    @Valid
    @NotNull
    private final Payments payments = new Payments();


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

    public Cluster getCluster() {
        return cluster;
    }

    public Payments getPayments() {
        return payments;
    }

    /**
     * Crypto wallet: on-chain deposits + KYC-gated withdrawals. Default OFF — when disabled the REST/webhook
     * endpoints reject, so the subsystem is inert until explicitly enabled and a provider is configured.
     */
    public static class Payments {

        /** Master switch for the wallet subsystem (deposits + withdrawals). */
        private boolean enabled = false;

        /** Whether a withdrawal requires the user's KYC to be VERIFIED. */
        private boolean kycRequiredForWithdrawal = true;

        /** Shared secret authenticating provider webhooks (deposit-confirmed, kyc-callback). */
        private String webhookSecret = "";

        /** Active payment provider: {@code mock} (default, no network), {@code gateway} (real HTTP gateway),
         *  {@code eth-self-custody} (in-process ETH key derivation, deposits only), or {@code offline-pool}
         *  (deposit addresses pre-generated offline and served from a watch-only pool; private keys never on
         *  the server). */
        private String provider = "mock";

        /** Network the gateway operates on: {@code testnet} (default) or {@code mainnet}. Informational + sent
         *  to the gateway; the actual endpoint is chosen by {@code gatewayBaseUrl} (sandbox vs prod). */
        private String network = "testnet";

        /** Gateway REST base URL (e.g. a sandbox/testnet endpoint). Empty until configured. */
        private String gatewayBaseUrl = "";

        /** Gateway API key (sent as the x-api-key header). */
        private String gatewayApiKey = "";

        /** Master key for the {@code eth-self-custody} provider's deterministic address derivation.
         *  DEMO ONLY — production must hold this in an HSM and derive watch-only from a BIP-32 xpub. */
        private String selfCustodyMasterKey = "";

        /** Minimum on-chain confirmations before a detected deposit is credited (watch-only ingestion). */
        private int minConfirmations = 1;

        /** Directory where KYC verification videos are stored (metadata is kept in the DB). In a clustered
         *  deployment this must be a shared volume so any node can read what another node received. */
        private String kycStorageDir = "";

        /** Max size (bytes) of a KYC verification upload. Default 50 MB. */
        private long kycMaxUploadBytes = 52_428_800L;

        /** Base64 AES key (16/24/32 bytes) encrypting KYC videos at rest. Empty = store plaintext. */
        private String kycEncryptionKey = "";

        /** GDPR retention: delete KYC verification media older than this many days. 0 = never auto-delete. */
        private int kycRetentionDays = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isKycRequiredForWithdrawal() {
            return kycRequiredForWithdrawal;
        }

        public void setKycRequiredForWithdrawal(boolean kycRequiredForWithdrawal) {
            this.kycRequiredForWithdrawal = kycRequiredForWithdrawal;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getNetwork() {
            return network;
        }

        public void setNetwork(String network) {
            this.network = network;
        }

        public String getGatewayBaseUrl() {
            return gatewayBaseUrl;
        }

        public void setGatewayBaseUrl(String gatewayBaseUrl) {
            this.gatewayBaseUrl = gatewayBaseUrl;
        }

        public String getGatewayApiKey() {
            return gatewayApiKey;
        }

        public void setGatewayApiKey(String gatewayApiKey) {
            this.gatewayApiKey = gatewayApiKey;
        }

        public String getSelfCustodyMasterKey() {
            return selfCustodyMasterKey;
        }

        public void setSelfCustodyMasterKey(String selfCustodyMasterKey) {
            this.selfCustodyMasterKey = selfCustodyMasterKey;
        }

        public int getMinConfirmations() {
            return minConfirmations;
        }

        public void setMinConfirmations(int minConfirmations) {
            this.minConfirmations = minConfirmations;
        }

        public String getKycStorageDir() {
            return kycStorageDir;
        }

        public void setKycStorageDir(String kycStorageDir) {
            this.kycStorageDir = kycStorageDir;
        }

        public long getKycMaxUploadBytes() {
            return kycMaxUploadBytes;
        }

        public void setKycMaxUploadBytes(long kycMaxUploadBytes) {
            this.kycMaxUploadBytes = kycMaxUploadBytes;
        }

        public String getKycEncryptionKey() {
            return kycEncryptionKey;
        }

        public void setKycEncryptionKey(String kycEncryptionKey) {
            this.kycEncryptionKey = kycEncryptionKey;
        }

        public int getKycRetentionDays() {
            return kycRetentionDays;
        }

        public void setKycRetentionDays(int kycRetentionDays) {
            this.kycRetentionDays = kycRetentionDays;
        }
    }

    /** Engine-migration Phase 5: per-table ownership for multi-node clustering. */
    public static class Cluster {
        /** When true, schedulers only run for tables this node owns (Redis lease). Default off = single node. */
        private boolean ownershipEnabled = false;

        /** Ownership lease TTL; a dead owner's tables become claimable after this. */
        @Min(1000)
        private long leaseTtlMillis = 30_000;

        /**
         * When true (requires ownershipEnabled), an action received for a table owned by another node
         * is forwarded over HTTP to that owner. Default off → no cross-node forwarding.
         */
        private boolean routingEnabled = false;

        /** This node's peer-reachable base URL for forwarded calls, e.g. http://host:8080. */
        private String nodeBaseUrl = "";

        /** Shared secret authenticating node-to-node forwarded calls (required when routing on). */
        private String sharedSecret = "";

        /**
         * When true (requires ownershipEnabled), each node periodically scans for active tables whose
         * owner died (lease expired) and takes them over — re-acquiring ownership and resuming the
         * stalled turn timer. Default off → orphaned tables resume only lazily on the next action.
         */
        private boolean takeoverEnabled = false;

        /**
         * Behaviour when ownership is enabled but Redis is unreachable. Default (false, fail-open) keeps a
         * surviving node playable by assuming it owns its tables — correct for single-node / brief blips,
         * but in a true cluster a partitioned node could then double-own a table (split-brain). When true
         * (fail-closed), a node that cannot consult Redis refuses ownership (acquire/isOwner → false), so it
         * stops driving timers / claiming tables until Redis is reachable again — trading availability for
         * safety against two nodes mutating the same table.
         */
        private boolean failClosed = false;

        /**
         * When true (requires ownershipEnabled + hot-state), each lease acquisition carries a monotonic
         * fencing token; the authoritative Redis hot-state write atomically rejects a write whose token is
         * behind the table's current token. This stops a paused/stale former owner (e.g. after a GC pause
         * during which its lease expired and another node took over) from clobbering the new owner's state.
         * Default off → no fencing.
         */
        private boolean fencingEnabled = false;

        public boolean isOwnershipEnabled() {
            return ownershipEnabled;
        }

        public void setOwnershipEnabled(boolean ownershipEnabled) {
            this.ownershipEnabled = ownershipEnabled;
        }

        public long getLeaseTtlMillis() {
            return leaseTtlMillis;
        }

        public void setLeaseTtlMillis(long leaseTtlMillis) {
            this.leaseTtlMillis = leaseTtlMillis;
        }

        public boolean isRoutingEnabled() {
            return routingEnabled;
        }

        public void setRoutingEnabled(boolean routingEnabled) {
            this.routingEnabled = routingEnabled;
        }

        public String getNodeBaseUrl() {
            return nodeBaseUrl;
        }

        public void setNodeBaseUrl(String nodeBaseUrl) {
            this.nodeBaseUrl = nodeBaseUrl;
        }

        public String getSharedSecret() {
            return sharedSecret;
        }

        public void setSharedSecret(String sharedSecret) {
            this.sharedSecret = sharedSecret;
        }

        public boolean isTakeoverEnabled() {
            return takeoverEnabled;
        }

        public void setTakeoverEnabled(boolean takeoverEnabled) {
            this.takeoverEnabled = takeoverEnabled;
        }

        public boolean isFailClosed() {
            return failClosed;
        }

        public void setFailClosed(boolean failClosed) {
            this.failClosed = failClosed;
        }

        public boolean isFencingEnabled() {
            return fencingEnabled;
        }

        public void setFencingEnabled(boolean fencingEnabled) {
            this.fencingEnabled = fencingEnabled;
        }
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

        /** {@link BotMode#ADVANCED} by default; use PASSIVE for mass bot / load tests. */
        private BotMode botMode = BotMode.ADVANCED;

        /** {@link GameEngine#LEGACY} by default; {@link GameEngine#AGGREGATE} uses domain.PokerGame. */
        private GameEngine engine = GameEngine.LEGACY;

        /** Seconds a human player has to act before auto-check/auto-fold. */
        @Min(1)
        private int turnActionTimeoutSeconds = 30;

        /** Seconds to keep hand results visible before server starts the next hand. */
        @Min(1)
        private int handResultDelaySeconds = 3;

        /**
         * Phase 2: serialize all mutations for a table through a single-writer queue and apply
         * commandId-based idempotency. Default off keeps the legacy (lock-free) path for rollback.
         */
        private boolean singleWriterEnabled = false;

        /** Worker threads shared across all tables; 0 → {@code max(8, cores*2)}. */
        @Min(0)
        private int singleWriterPoolSize = 0;

        /** Bounded submit queue across all tables; excess submits are rejected fast. */
        @Min(1)
        private int singleWriterQueueCapacity = 10_000;

        /** Max time a request waits for its table command to run before failing. */
        @Min(1)
        private long singleWriterAwaitMillis = 10_000;

        /** How long a processed commandId stays cached for idempotent replay. */
        @Min(1)
        private long commandDedupTtlMillis = 60_000;

        /** Max distinct commandIds remembered per table (LRU). */
        @Min(1)
        private int commandDedupMaxPerGame = 256;

        /**
         * Phase 4: persist published domain events to the append-only game_event_log (audit + replay).
         * Only fills on the aggregate engine path; default on, flip off to disable the audit writer.
         */
        private boolean eventLogEnabled = true;


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

        public BotMode getBotMode() {
            return botMode;
        }

        public void setBotMode(BotMode botMode) {
            this.botMode = botMode;
        }

        public GameEngine getEngine() {
            return engine;
        }

        public void setEngine(GameEngine engine) {
            this.engine = engine;
        }

        public int getTurnActionTimeoutSeconds() {
            return turnActionTimeoutSeconds;
        }

        public void setTurnActionTimeoutSeconds(int turnActionTimeoutSeconds) {
            this.turnActionTimeoutSeconds = turnActionTimeoutSeconds;
        }

        public int getHandResultDelaySeconds() {
            return handResultDelaySeconds;
        }

        public void setHandResultDelaySeconds(int handResultDelaySeconds) {
            this.handResultDelaySeconds = handResultDelaySeconds;
        }

        public boolean isSingleWriterEnabled() {
            return singleWriterEnabled;
        }

        public void setSingleWriterEnabled(boolean singleWriterEnabled) {
            this.singleWriterEnabled = singleWriterEnabled;
        }

        public int getSingleWriterPoolSize() {
            return singleWriterPoolSize;
        }

        public void setSingleWriterPoolSize(int singleWriterPoolSize) {
            this.singleWriterPoolSize = singleWriterPoolSize;
        }

        public int getSingleWriterQueueCapacity() {
            return singleWriterQueueCapacity;
        }

        public void setSingleWriterQueueCapacity(int singleWriterQueueCapacity) {
            this.singleWriterQueueCapacity = singleWriterQueueCapacity;
        }

        public long getSingleWriterAwaitMillis() {
            return singleWriterAwaitMillis;
        }

        public void setSingleWriterAwaitMillis(long singleWriterAwaitMillis) {
            this.singleWriterAwaitMillis = singleWriterAwaitMillis;
        }

        public long getCommandDedupTtlMillis() {
            return commandDedupTtlMillis;
        }

        public void setCommandDedupTtlMillis(long commandDedupTtlMillis) {
            this.commandDedupTtlMillis = commandDedupTtlMillis;
        }

        public int getCommandDedupMaxPerGame() {
            return commandDedupMaxPerGame;
        }

        public void setCommandDedupMaxPerGame(int commandDedupMaxPerGame) {
            this.commandDedupMaxPerGame = commandDedupMaxPerGame;
        }

        public boolean isEventLogEnabled() {
            return eventLogEnabled;
        }

        public void setEventLogEnabled(boolean eventLogEnabled) {
            this.eventLogEnabled = eventLogEnabled;
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

        /** Max registrations embedded in GET tournament detail (larger fields use leaderboard API). */
        @Min(1)
        private int detailMaxRegistrations = 500;

        /** Max active tables with seated players in GET tournament detail. */
        @Min(1)
        private int detailMaxTables = 100;

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

        @Min(1)
        private int pyramidDefaultSeatsPerTable = 10;

        @Min(1)
        private int pyramidDefaultHandsPerRound = 3;

        /** Parallel workers when simulating pyramid table rounds (load tests). */
        @Min(1)
        private int pyramidTableParallelism = 8;

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

        public int getDetailMaxRegistrations() {
            return detailMaxRegistrations;
        }

        public void setDetailMaxRegistrations(int detailMaxRegistrations) {
            this.detailMaxRegistrations = detailMaxRegistrations;
        }

        public int getDetailMaxTables() {
            return detailMaxTables;
        }

        public void setDetailMaxTables(int detailMaxTables) {
            this.detailMaxTables = detailMaxTables;
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

        public int getPyramidDefaultSeatsPerTable() {
            return pyramidDefaultSeatsPerTable;
        }

        public void setPyramidDefaultSeatsPerTable(int pyramidDefaultSeatsPerTable) {
            this.pyramidDefaultSeatsPerTable = pyramidDefaultSeatsPerTable;
        }

        public int getPyramidDefaultHandsPerRound() {
            return pyramidDefaultHandsPerRound;
        }

        public void setPyramidDefaultHandsPerRound(int pyramidDefaultHandsPerRound) {
            this.pyramidDefaultHandsPerRound = pyramidDefaultHandsPerRound;
        }

        public int getPyramidTableParallelism() {
            return pyramidTableParallelism;
        }

        public void setPyramidTableParallelism(int pyramidTableParallelism) {
            this.pyramidTableParallelism = pyramidTableParallelism;
        }
    }
}
