package com.truholdem.config;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Valid
    @NotNull
    private final Cash cash = new Cash();


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

    public Cash getCash() {
        return cash;
    }

    /**
     * Cash (ring) tables. Default OFF — when disabled the cash REST endpoints reject (404), so the subsystem is
     * inert until explicitly enabled. Real-money buy-ins additionally require {@code app.payments.enabled}.
     */
    public static class Cash {

        /** Master switch for the cash-table REST surface (lobby, sit/leave, deal/act, state). */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
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

        /** Whether a withdrawal must be manually approved by a moderator before it is broadcast. When on,
         *  a request is debited and parked in PENDING_APPROVAL until an admin approves (→ broadcast) or
         *  rejects (→ reversal). Off (default) keeps the immediate-broadcast behaviour. */
        private boolean withdrawalApprovalRequired = false;

        /** Max amount per single withdrawal, keyed by {@link com.truholdem.model.CryptoAsset} name. An asset
         *  with no entry has no per-transaction limit. */
        private Map<String, BigDecimal> maxWithdrawalPerTx = new HashMap<>();

        /** Max total withdrawal amount per rolling 24h per user, keyed by asset name. No entry = no limit. */
        private Map<String, BigDecimal> maxWithdrawalPerDay = new HashMap<>();

        /** Operator-declared custodied on-chain reserve float (hot+cold treasury) per asset name, e.g.
         *  {@code USDT_TRC20 -> 50000}. The solvency monitor compares total user liabilities (Σ of all wallet
         *  balances) plus in-flight withdrawals against this figure and alerts as they approach it. We cannot
         *  reliably read the treasury balance from inside the app (keys are offline / addresses are watch-only),
         *  so this is an operator input — keep it current. No entry for an asset = no reference: the liabilities
         *  gauges still publish, but the float gauge reports NaN and the solvency alert stays dormant for it. */
        private Map<String, BigDecimal> reserveFloat = new HashMap<>();

        /** Mandatory delay (minutes) between a withdrawal request and when it may be approved/executed — a
         *  fraud-detection window. 0 (default) disables it. Only meaningful with approval required. */
        private int withdrawalCoolingPeriodMinutes = 0;

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

        /** Online ETH/ERC-20 withdrawal coordinator: assembles an unsigned tx from live node state (nonce,
         *  gas price, chain id), broadcasts the offline-signed raw tx, and reconciles receipts → CONFIRMED.
         *  Signing stays offline (never on the server). Inert unless {@code ethRpcEnabled}. */
        private boolean ethRpcEnabled = false;
        /** JSON-RPC endpoint of the Ethereum node (e.g. http://geth:8545). */
        private String ethRpcUrl = "";
        /** Chain id; 0 = query the node ({@code eth_chainId}). */
        private long ethChainId = 0;
        /** The treasury/hot sending address whose key signs offline (its nonce is read from the node). */
        private String ethFromAddress = "";
        /** Gas limit for a native ETH transfer. */
        private long ethGasLimit = 21000;
        /** Gas limit for an ERC-20 {@code transfer(address,uint256)}. */
        private long erc20GasLimit = 100000;
        /** ERC-20 contract address per asset name (e.g. {@code USDT_ERC20 -> 0x...}). */
        private Map<String, String> erc20Contracts = new HashMap<>();

        /** Online BTC (P2WPKH) withdrawal coordinator: selects UTXOs + fee from a Bitcoin Core node, assembles
         *  an unsigned tx for the offline signer, broadcasts the signed raw tx, and reconciles confirmations.
         *  Signing stays offline. Inert unless {@code btcRpcEnabled}. */
        private boolean btcRpcEnabled = false;
        /** Bitcoin Core JSON-RPC endpoint (e.g. http://bitcoind:18443) + HTTP-Basic credentials. */
        private String btcRpcUrl = "";
        private String btcRpcUser = "";
        private String btcRpcPassword = "";
        /** Network selecting the bech32 hrp: {@code mainnet} (bc), {@code testnet} (tb), {@code regtest} (bcrt). */
        private String btcNetwork = "mainnet";
        /** The treasury/hot P2WPKH address whose key signs offline (its UTXOs fund withdrawals). */
        private String btcFromAddress = "";
        /** Flat fee rate (sat/vByte) used to size the fee (regtest/estimate-less). */
        private long btcFeeRateSatPerVbyte = 5;
        /** Minimum confirmations a UTXO needs before the coordinator will spend it. */
        private int btcMinUtxoConfirmations = 1;

        /** Deposit→treasury sweep (consolidation): gathers UTXOs from watch-only deposit addresses into the
         *  treasury {@code btc-from-address}. Off by default; signing stays offline. */
        @Valid
        private Sweep sweep = new Sweep();

        /** Periodically reconcile BROADCAST withdrawals against the chain via the ETH/BTC coordinators
         *  (→ CONFIRMED / FAILED). Idempotent, so safe on every node. Inert unless payments are enabled. */
        private boolean withdrawalReconcileEnabled = false;

        /** KYC media backend: {@code filesystem} (default) or {@code s3} (S3/MinIO object storage). */
        private String kycStorageType = "filesystem";

        /** Directory where KYC verification videos are stored (filesystem backend; metadata is in the DB).
         *  In a cluster this must be a shared volume so any node can read what another node received. */
        private String kycStorageDir = "";

        /** S3/MinIO object storage (when kyc-storage-type=s3): endpoint, bucket, region, credentials. */
        private String s3Endpoint = "";
        private String s3Bucket = "";
        private String s3Region = "us-east-1";
        private String s3AccessKey = "";
        private String s3SecretKey = "";

        /** Max size (bytes) of a KYC verification upload. Default 50 MB. */
        private long kycMaxUploadBytes = 52_428_800L;

        /** Legacy single base64 AES key encrypting KYC videos at rest. Empty = plaintext. Superseded by the
         *  keyring below (kept for backward compatibility — exposed as key id "default"). */
        private String kycEncryptionKey = "";

        /** Versioned KYC encryption keyring: key id → base64 AES key. New uploads use {@code kycActiveKeyId},
         *  and each document records the key id it was encrypted with, so old keys still decrypt after a
         *  rotation. (Seam for a KMS-backed key provider — a drop-in replacement for this config map.) */
        private Map<String, String> kycEncryptionKeys = new HashMap<>();

        /** Key id (from the keyring / legacy "default") used to encrypt new KYC uploads. */
        private String kycActiveKeyId = "";

        /** Which KYC key provider supplies encryption keys: "config" (the keyring above) or "kms" (AWS KMS
         *  envelope encryption — the data key is minted/unwrapped by KMS and never lives in config). */
        private String kycKeyProvider = "config";

        /** AWS KMS (when {@code kycKeyProvider=kms}): the CMK key id/arn/alias and credentials/region. The
         *  endpoint defaults to {@code https://kms.<region>.amazonaws.com} but can be overridden (e.g. for a
         *  local KMS-compatible server / LocalStack). */
        private String kmsKeyId = "";
        private String kmsRegion = "us-east-1";
        private String kmsAccessKey = "";
        private String kmsSecretKey = "";
        private String kmsEndpoint = "";

        /** Scan KYC uploads for malware via a ClamAV daemon (clamd, INSTREAM) before storing. */
        private boolean kycAvScanEnabled = false;
        private String clamavHost = "localhost";
        private int clamavPort = 3310;

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

        public boolean isWithdrawalApprovalRequired() {
            return withdrawalApprovalRequired;
        }

        public void setWithdrawalApprovalRequired(boolean withdrawalApprovalRequired) {
            this.withdrawalApprovalRequired = withdrawalApprovalRequired;
        }

        public Map<String, BigDecimal> getMaxWithdrawalPerTx() {
            return maxWithdrawalPerTx;
        }

        public void setMaxWithdrawalPerTx(Map<String, BigDecimal> maxWithdrawalPerTx) {
            this.maxWithdrawalPerTx = maxWithdrawalPerTx;
        }

        public Map<String, BigDecimal> getMaxWithdrawalPerDay() {
            return maxWithdrawalPerDay;
        }

        public void setMaxWithdrawalPerDay(Map<String, BigDecimal> maxWithdrawalPerDay) {
            this.maxWithdrawalPerDay = maxWithdrawalPerDay;
        }

        public Map<String, BigDecimal> getReserveFloat() {
            return reserveFloat;
        }

        public void setReserveFloat(Map<String, BigDecimal> reserveFloat) {
            this.reserveFloat = reserveFloat;
        }

        public int getWithdrawalCoolingPeriodMinutes() {
            return withdrawalCoolingPeriodMinutes;
        }

        public void setWithdrawalCoolingPeriodMinutes(int withdrawalCoolingPeriodMinutes) {
            this.withdrawalCoolingPeriodMinutes = withdrawalCoolingPeriodMinutes;
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

        public boolean isEthRpcEnabled() {
            return ethRpcEnabled;
        }

        public void setEthRpcEnabled(boolean ethRpcEnabled) {
            this.ethRpcEnabled = ethRpcEnabled;
        }

        public String getEthRpcUrl() {
            return ethRpcUrl;
        }

        public void setEthRpcUrl(String ethRpcUrl) {
            this.ethRpcUrl = ethRpcUrl;
        }

        public long getEthChainId() {
            return ethChainId;
        }

        public void setEthChainId(long ethChainId) {
            this.ethChainId = ethChainId;
        }

        public String getEthFromAddress() {
            return ethFromAddress;
        }

        public void setEthFromAddress(String ethFromAddress) {
            this.ethFromAddress = ethFromAddress;
        }

        public long getEthGasLimit() {
            return ethGasLimit;
        }

        public void setEthGasLimit(long ethGasLimit) {
            this.ethGasLimit = ethGasLimit;
        }

        public long getErc20GasLimit() {
            return erc20GasLimit;
        }

        public void setErc20GasLimit(long erc20GasLimit) {
            this.erc20GasLimit = erc20GasLimit;
        }

        public Map<String, String> getErc20Contracts() {
            return erc20Contracts;
        }

        public void setErc20Contracts(Map<String, String> erc20Contracts) {
            this.erc20Contracts = erc20Contracts;
        }

        public boolean isBtcRpcEnabled() {
            return btcRpcEnabled;
        }

        public void setBtcRpcEnabled(boolean btcRpcEnabled) {
            this.btcRpcEnabled = btcRpcEnabled;
        }

        public String getBtcRpcUrl() {
            return btcRpcUrl;
        }

        public void setBtcRpcUrl(String btcRpcUrl) {
            this.btcRpcUrl = btcRpcUrl;
        }

        public String getBtcRpcUser() {
            return btcRpcUser;
        }

        public void setBtcRpcUser(String btcRpcUser) {
            this.btcRpcUser = btcRpcUser;
        }

        public String getBtcRpcPassword() {
            return btcRpcPassword;
        }

        public void setBtcRpcPassword(String btcRpcPassword) {
            this.btcRpcPassword = btcRpcPassword;
        }

        public String getBtcNetwork() {
            return btcNetwork;
        }

        public void setBtcNetwork(String btcNetwork) {
            this.btcNetwork = btcNetwork;
        }

        public String getBtcFromAddress() {
            return btcFromAddress;
        }

        public void setBtcFromAddress(String btcFromAddress) {
            this.btcFromAddress = btcFromAddress;
        }

        public long getBtcFeeRateSatPerVbyte() {
            return btcFeeRateSatPerVbyte;
        }

        public void setBtcFeeRateSatPerVbyte(long btcFeeRateSatPerVbyte) {
            this.btcFeeRateSatPerVbyte = btcFeeRateSatPerVbyte;
        }

        public int getBtcMinUtxoConfirmations() {
            return btcMinUtxoConfirmations;
        }

        public void setBtcMinUtxoConfirmations(int btcMinUtxoConfirmations) {
            this.btcMinUtxoConfirmations = btcMinUtxoConfirmations;
        }

        public Sweep getSweep() {
            return sweep;
        }

        public void setSweep(Sweep sweep) {
            this.sweep = sweep;
        }

        public boolean isWithdrawalReconcileEnabled() {
            return withdrawalReconcileEnabled;
        }

        public void setWithdrawalReconcileEnabled(boolean withdrawalReconcileEnabled) {
            this.withdrawalReconcileEnabled = withdrawalReconcileEnabled;
        }

        public String getKycStorageType() {
            return kycStorageType;
        }

        public void setKycStorageType(String kycStorageType) {
            this.kycStorageType = kycStorageType;
        }

        public String getKycStorageDir() {
            return kycStorageDir;
        }

        public void setKycStorageDir(String kycStorageDir) {
            this.kycStorageDir = kycStorageDir;
        }

        public String getS3Endpoint() {
            return s3Endpoint;
        }

        public void setS3Endpoint(String s3Endpoint) {
            this.s3Endpoint = s3Endpoint;
        }

        public String getS3Bucket() {
            return s3Bucket;
        }

        public void setS3Bucket(String s3Bucket) {
            this.s3Bucket = s3Bucket;
        }

        public String getS3Region() {
            return s3Region;
        }

        public void setS3Region(String s3Region) {
            this.s3Region = s3Region;
        }

        public String getS3AccessKey() {
            return s3AccessKey;
        }

        public void setS3AccessKey(String s3AccessKey) {
            this.s3AccessKey = s3AccessKey;
        }

        public String getS3SecretKey() {
            return s3SecretKey;
        }

        public void setS3SecretKey(String s3SecretKey) {
            this.s3SecretKey = s3SecretKey;
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

        public Map<String, String> getKycEncryptionKeys() {
            return kycEncryptionKeys;
        }

        public void setKycEncryptionKeys(Map<String, String> kycEncryptionKeys) {
            this.kycEncryptionKeys = kycEncryptionKeys;
        }

        public String getKycActiveKeyId() {
            return kycActiveKeyId;
        }

        public void setKycActiveKeyId(String kycActiveKeyId) {
            this.kycActiveKeyId = kycActiveKeyId;
        }

        public String getKycKeyProvider() {
            return kycKeyProvider;
        }

        public void setKycKeyProvider(String kycKeyProvider) {
            this.kycKeyProvider = kycKeyProvider;
        }

        public String getKmsKeyId() {
            return kmsKeyId;
        }

        public void setKmsKeyId(String kmsKeyId) {
            this.kmsKeyId = kmsKeyId;
        }

        public String getKmsRegion() {
            return kmsRegion;
        }

        public void setKmsRegion(String kmsRegion) {
            this.kmsRegion = kmsRegion;
        }

        public String getKmsAccessKey() {
            return kmsAccessKey;
        }

        public void setKmsAccessKey(String kmsAccessKey) {
            this.kmsAccessKey = kmsAccessKey;
        }

        public String getKmsSecretKey() {
            return kmsSecretKey;
        }

        public void setKmsSecretKey(String kmsSecretKey) {
            this.kmsSecretKey = kmsSecretKey;
        }

        public String getKmsEndpoint() {
            return kmsEndpoint;
        }

        public void setKmsEndpoint(String kmsEndpoint) {
            this.kmsEndpoint = kmsEndpoint;
        }

        public boolean isKycAvScanEnabled() {
            return kycAvScanEnabled;
        }

        public void setKycAvScanEnabled(boolean kycAvScanEnabled) {
            this.kycAvScanEnabled = kycAvScanEnabled;
        }

        public String getClamavHost() {
            return clamavHost;
        }

        public void setClamavHost(String clamavHost) {
            this.clamavHost = clamavHost;
        }

        public int getClamavPort() {
            return clamavPort;
        }

        public void setClamavPort(int clamavPort) {
            this.clamavPort = clamavPort;
        }

        public int getKycRetentionDays() {
            return kycRetentionDays;
        }

        public void setKycRetentionDays(int kycRetentionDays) {
            this.kycRetentionDays = kycRetentionDays;
        }

        /** Deposit→treasury sweep config. Off by default; the treasury target reuses {@code btc-from-address}. */
        public static class Sweep {
            /** Master switch for the sweep coordinator's operations (the coordinator bean still needs
             *  {@code btc-rpc-enabled}). */
            private boolean enabled = false;
            /** Per-asset minimum UTXO value to sweep (dust filter), keyed by {@link CryptoAsset#name()},
             *  e.g. {@code app.payments.sweep.min-amount-per-asset.BTC=0.001}. Absent = no minimum. */
            private Map<String, BigDecimal> minAmountPerAsset = new HashMap<>();
            /** Max inputs consolidated in one sweep tx (bounds tx size / fee). */
            @Min(1)
            private int batchMaxInputs = 10;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public Map<String, BigDecimal> getMinAmountPerAsset() {
                return minAmountPerAsset;
            }

            public void setMinAmountPerAsset(Map<String, BigDecimal> minAmountPerAsset) {
                this.minAmountPerAsset = minAmountPerAsset;
            }

            public int getBatchMaxInputs() {
                return batchMaxInputs;
            }

            public void setBatchMaxInputs(int batchMaxInputs) {
                this.batchMaxInputs = batchMaxInputs;
            }
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

        /** Periodically auto-start tournaments whose {@code scheduledStart} has passed (if minPlayers met).
         *  Cluster-safe via the tournament's status guard + optimistic lock. Off by default. */
        private boolean scheduledStartEnabled = false;

        /** Minimum registration runway when pinning a tournament to a time of day: if today's slot is closer
         *  than this, the next day's slot is used. */
        @Min(0)
        private int scheduledStartRunwayHours = 3;

        /** Zone in which a time-of-day slot is interpreted. */
        private String scheduledStartZone = "UTC";

        /** When a scheduled (non-full-required) tournament is under minPlayers at its slot, cancel it and
         *  refund real-money buy-ins instead of leaving it REGISTERING. */
        private boolean cancelUnderfilledScheduled = true;

        /** Buy-up pyramid: maximum number of higher-level seat buy-outs allowed per tournament. */
        @Min(0)
        private int pyramidMaxBuyouts = 10;

        /** Federated pyramid: master flag for the sharded very-large pyramid tournament type. */
        private boolean federatedPyramidEnabled = false;

        /** Federated pyramid: default players per shard (each shard is a sub-pyramid run to one winner). */
        @Min(2)
        private int federatedDefaultShardSize = 10_000;

        /** Federated pyramid: max shards materialized/running at once (wave concurrency cap). */
        @Min(1)
        private int federatedMaxConcurrentShards = 8;

        /** Federated pyramid: how many physical node-groups shards are round-robin pinned across. */
        @Min(1)
        private int federatedNodeGroupCount = 1;

        /** Federated pyramid (real money): the qualifier paid to EACH shard winner, in parts-per-million of the
         * net prize pool (i.e. after the organisation fee), deducted from the champion's remainder. Default
         * 100 ppm = 0.01% of the WHOLE federation pool per winner (so the canonical 100-shard field pays out 1%
         * of the pool across the shard winners). The organisation fee itself is {@code feeBasisPoints} (≤20%). */
        @Min(0)
        private int federatedShardWinnerPpm = 100;

        /** Federated pyramid (real money): per-place prize share (basis points of the net pool) for the
         * non-champion FINAL-TABLE places — index 0 = 2nd place, 1 = 3rd, … Default {@code [300,100]} = 3% + 1%.
         * The grand champion takes the remainder (pool − shard qualifiers − these places), so the pool always
         * sums to 100%. Bound from a comma-separated property, e.g. {@code app.tournament.federated-final-table-place-bps=300,100}. */
        private List<Integer> federatedFinalTablePlaceBps = List.of(300, 100);

        /** Federated pyramid (real money): basis points of the net pool split equally among the remaining
         * final-table players (those past {@code federatedFinalTablePlaceBps}). Default 100 = 1%. */
        @Min(0)
        private int federatedFinalTableRestBps = 100;

        public int getFederatedShardWinnerPpm() {
            return federatedShardWinnerPpm;
        }

        public void setFederatedShardWinnerPpm(int federatedShardWinnerPpm) {
            this.federatedShardWinnerPpm = federatedShardWinnerPpm;
        }

        public List<Integer> getFederatedFinalTablePlaceBps() {
            return federatedFinalTablePlaceBps;
        }

        public void setFederatedFinalTablePlaceBps(List<Integer> federatedFinalTablePlaceBps) {
            this.federatedFinalTablePlaceBps = federatedFinalTablePlaceBps;
        }

        public int getFederatedFinalTableRestBps() {
            return federatedFinalTableRestBps;
        }

        public void setFederatedFinalTableRestBps(int federatedFinalTableRestBps) {
            this.federatedFinalTableRestBps = federatedFinalTableRestBps;
        }

        public int getFederatedMaxConcurrentShards() {
            return federatedMaxConcurrentShards;
        }

        public void setFederatedMaxConcurrentShards(int federatedMaxConcurrentShards) {
            this.federatedMaxConcurrentShards = federatedMaxConcurrentShards;
        }

        public int getFederatedNodeGroupCount() {
            return federatedNodeGroupCount;
        }

        public void setFederatedNodeGroupCount(int federatedNodeGroupCount) {
            this.federatedNodeGroupCount = federatedNodeGroupCount;
        }

        public boolean isFederatedPyramidEnabled() {
            return federatedPyramidEnabled;
        }

        public void setFederatedPyramidEnabled(boolean federatedPyramidEnabled) {
            this.federatedPyramidEnabled = federatedPyramidEnabled;
        }

        public int getFederatedDefaultShardSize() {
            return federatedDefaultShardSize;
        }

        public void setFederatedDefaultShardSize(int federatedDefaultShardSize) {
            this.federatedDefaultShardSize = federatedDefaultShardSize;
        }

        public boolean isScheduledStartEnabled() {
            return scheduledStartEnabled;
        }

        public void setScheduledStartEnabled(boolean scheduledStartEnabled) {
            this.scheduledStartEnabled = scheduledStartEnabled;
        }

        public int getScheduledStartRunwayHours() {
            return scheduledStartRunwayHours;
        }

        public void setScheduledStartRunwayHours(int scheduledStartRunwayHours) {
            this.scheduledStartRunwayHours = scheduledStartRunwayHours;
        }

        public String getScheduledStartZone() {
            return scheduledStartZone;
        }

        public void setScheduledStartZone(String scheduledStartZone) {
            this.scheduledStartZone = scheduledStartZone;
        }

        public boolean isCancelUnderfilledScheduled() {
            return cancelUnderfilledScheduled;
        }

        public void setCancelUnderfilledScheduled(boolean cancelUnderfilledScheduled) {
            this.cancelUnderfilledScheduled = cancelUnderfilledScheduled;
        }

        public int getPyramidMaxBuyouts() {
            return pyramidMaxBuyouts;
        }

        public void setPyramidMaxBuyouts(int pyramidMaxBuyouts) {
            this.pyramidMaxBuyouts = pyramidMaxBuyouts;
        }

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
