package com.truholdem.service.wallet.sol;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.AppProperties;

/**
 * Minimal Solana JSON-RPC 2.0 client (pure {@code RestClient} + Jackson, no solana-web3 dependency). Reads the
 * recent blockhash (the tx "nonce"/expiry), SPL token-account balances + accounts-by-owner, broadcasts a signed
 * (base64) transaction, and reads a signature's confirmation status. Account model — the coordinator builds an
 * SPL transfer and signs it offline (ed25519). Active when {@code app.payments.sol-rpc-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.sol-rpc-enabled", havingValue = "true")
public class SolanaRpcClient {

    /** Recent blockhash + the last block height at which a tx using it is still valid. */
    public record Blockhash(String blockhash, long lastValidBlockHeight) {
    }

    /** An SPL token account owned by a wallet for a mint: its address + raw (base-unit) balance. */
    public record TokenAccount(String pubkey, BigInteger amount) {
    }

    /** A transaction signature's status. {@code confirmations == -1} means finalized (RPC returns null). */
    public record SignatureStatus(boolean found, long confirmations, String confirmationStatus, boolean failed) {
        public static final SignatureStatus NOT_FOUND = new SignatureStatus(false, 0, null, false);
    }

    private final AppProperties appProperties;
    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public SolanaRpcClient(AppProperties appProperties) {
        this.appProperties = appProperties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Latest blockhash at {@code finalized} commitment — the value a transaction must be signed against. Using
     *  {@code finalized} (not {@code confirmed}) guarantees the blockhash is already in the validator's recent
     *  blockhash queue, avoiding a {@code BlockhashNotFound} on submit. */
    public Blockhash getLatestBlockhash() {
        return parseBlockhash(rpc("getLatestBlockhash", List.of(Map.of("commitment", "finalized"))));
    }

    /** Raw (base-unit) balance of an SPL token account (e.g. an ATA). Throws if the account does not exist. */
    public BigInteger getTokenAccountBalance(String tokenAccount) {
        return parseTokenAmount(rpc("getTokenAccountBalance",
                List.of(tokenAccount, Map.of("commitment", "confirmed"))));
    }

    /** Lamport balance of an account (e.g. the operator) — used to confirm reclaimed ATA rent landed. Read at
     *  {@code confirmed} so a just-landed change is visible without waiting for finalization. */
    public BigInteger getBalance(String address) {
        return BigInteger.valueOf(rpc("getBalance",
                List.of(address, Map.of("commitment", "confirmed"))).path("value").asLong());
    }

    /** Token accounts owned by {@code owner} for {@code mint} (jsonParsed) — used to find/inspect an ATA. Read at
     *  {@code confirmed} so a freshly created mint/ATA is visible (the default {@code finalized} lags by ~32
     *  slots and rejects a not-yet-finalized mint with "could not find mint"). */
    public List<TokenAccount> getTokenAccountsByOwner(String owner, String mint) {
        return parseTokenAccounts(rpc("getTokenAccountsByOwner",
                List.of(owner, Map.of("mint", mint), Map.of("encoding", "jsonParsed", "commitment", "confirmed"))));
    }

    /** Raw (base-unit) balances of up to 100 token accounts (e.g. dedicated-wallet ATAs) in ONE call via
     *  {@code getMultipleAccounts} — the batched deposit poll uses this so a large field costs ceil(N/100) RPCs
     *  instead of N. Returns a map keyed by the input address; missing/uninitialized accounts are absent. Read at
     *  {@code confirmed}. */
    public Map<String, BigInteger> getTokenAccountBalances(List<String> tokenAccounts) {
        if (tokenAccounts.isEmpty()) {
            return Map.of();
        }
        return parseMultipleTokenAmounts(rpc("getMultipleAccounts",
                List.of(tokenAccounts, Map.of("encoding", "jsonParsed", "commitment", "confirmed"))), tokenAccounts);
    }

    /** Broadcast a base64-encoded signed transaction; returns its signature. Preflight runs at {@code confirmed}
     *  (not the default {@code finalized}) so a just-created/funded source account — visible at {@code confirmed}
     *  but not yet finalized — isn't rejected with a spurious {@code InvalidAccountData}. */
    public String sendTransaction(String base64SignedTx) {
        return rpc("sendTransaction",
                List.of(base64SignedTx, Map.of("encoding", "base64", "preflightCommitment", "confirmed"))).asText();
    }

    /** Confirmation status of a signature (searching history), or {@link SignatureStatus#NOT_FOUND}. */
    public SignatureStatus getSignatureStatus(String signature) {
        return parseSignatureStatus(rpc("getSignatureStatuses",
                List.of(List.of(signature), Map.of("searchTransactionHistory", true))));
    }

    // --- pure helpers (unit-tested against sample Solana JSON) ---

    static Blockhash parseBlockhash(JsonNode result) {
        JsonNode v = result.path("value");
        return new Blockhash(v.path("blockhash").asText(), v.path("lastValidBlockHeight").asLong());
    }

    static BigInteger parseTokenAmount(JsonNode result) {
        return new BigInteger(result.path("value").path("amount").asText("0"));
    }

    static List<TokenAccount> parseTokenAccounts(JsonNode result) {
        List<TokenAccount> out = new ArrayList<>();
        for (JsonNode acc : result.path("value")) {
            String amount = acc.path("account").path("data").path("parsed").path("info")
                    .path("tokenAmount").path("amount").asText("0");
            out.add(new TokenAccount(acc.path("pubkey").asText(), new BigInteger(amount)));
        }
        return out;
    }

    /** Map each input address to its parsed token-account balance (jsonParsed). {@code value} is positional and
     *  parallel to the requested addresses; a null entry (uninitialized account) is simply omitted. */
    static Map<String, BigInteger> parseMultipleTokenAmounts(JsonNode result, List<String> addresses) {
        java.util.Map<String, BigInteger> out = new java.util.HashMap<>();
        JsonNode value = result.path("value");
        for (int i = 0; i < addresses.size() && i < value.size(); i++) {
            JsonNode acc = value.get(i);
            if (acc == null || acc.isNull()) {
                continue;
            }
            JsonNode amount = acc.path("data").path("parsed").path("info").path("tokenAmount").path("amount");
            if (!amount.isMissingNode()) {
                out.put(addresses.get(i), new BigInteger(amount.asText("0")));
            }
        }
        return out;
    }

    static SignatureStatus parseSignatureStatus(JsonNode result) {
        JsonNode v = result.path("value").path(0);
        if (v.isMissingNode() || v.isNull()) {
            return SignatureStatus.NOT_FOUND;
        }
        long confirmations = v.path("confirmations").isNull() ? -1 : v.path("confirmations").asLong();
        boolean failed = !v.path("err").isNull() && !v.path("err").isMissingNode();
        return new SignatureStatus(true, confirmations, v.path("confirmationStatus").asText(null), failed);
    }

    static String requestBody(ObjectMapper mapper, String method, List<Object> params) {
        try {
            return mapper.writeValueAsString(
                    Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode Solana RPC request for " + method, e);
        }
    }

    private JsonNode rpc(String method, List<Object> params) {
        String url = appProperties.getPayments().getSolRpcUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("app.payments.sol-rpc-url is not configured");
        }
        try {
            String resp = http.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(mapper, method, params))
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(resp);
            if (root.has("error") && !root.get("error").isNull()) {
                throw new IllegalStateException("Solana RPC error from " + method + ": " + root.get("error"));
            }
            return root.get("result");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Solana RPC response for " + method, e);
        }
    }
}
