package com.truholdem.service.wallet.eth;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.truholdem.config.AppProperties;

/**
 * Minimal Ethereum JSON-RPC client (pure {@code RestClient} + Jackson, no web3j → the offline build adds no
 * dependency). Read-only chain queries plus {@code eth_sendRawTransaction} for broadcasting an
 * already-offline-signed raw transaction. Active when {@code app.payments.eth-rpc-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.eth-rpc-enabled", havingValue = "true")
public class EthRpcClient {

    /** A mined transaction receipt (present only once the tx is included in a block). */
    public record Receipt(String transactionHash, BigInteger blockNumber, boolean success) {
    }

    private final AppProperties appProperties;
    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong ids = new AtomicLong(1);

    public EthRpcClient(AppProperties appProperties) {
        this.appProperties = appProperties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public long chainId() {
        return EthAbi.hexToBigInteger(rpc("eth_chainId").asText()).longValueExact();
    }

    public BigInteger blockNumber() {
        return EthAbi.hexToBigInteger(rpc("eth_blockNumber").asText());
    }

    public BigInteger gasPrice() {
        return EthAbi.hexToBigInteger(rpc("eth_gasPrice").asText());
    }

    /** Pending nonce of an address (count of txs including those queued) — what the next tx must use. */
    public BigInteger pendingNonce(String address) {
        return EthAbi.hexToBigInteger(rpc("eth_getTransactionCount", address, "pending").asText());
    }

    public BigInteger balance(String address) {
        return EthAbi.hexToBigInteger(rpc("eth_getBalance", address, "latest").asText());
    }

    /** Broadcast an offline-signed raw transaction; returns its hash. */
    public String sendRawTransaction(String signedRawTxHex) {
        return rpc("eth_sendRawTransaction", signedRawTxHex).asText();
    }

    /** {@code eth_call} against a deployed contract (e.g. ERC-20 balanceOf); returns the 0x-hex result. */
    public String call(String to, String dataHex) {
        ObjectNode callObj = mapper.createObjectNode();
        callObj.put("to", to);
        callObj.put("data", dataHex);
        return rpc("eth_call", callObj, "latest").asText();
    }

    /** The receipt for a tx hash, or empty if it is not yet mined. */
    public Optional<Receipt> receipt(String txHash) {
        JsonNode node = rpc("eth_getTransactionReceipt", txHash);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        BigInteger blockNumber = EthAbi.hexToBigInteger(node.get("blockNumber").asText());
        boolean success = "0x1".equals(node.get("status").asText());
        return Optional.of(new Receipt(node.get("transactionHash").asText(), blockNumber, success));
    }

    /** Issue a JSON-RPC call and return the {@code result} node; throws on a JSON-RPC error. */
    private JsonNode rpc(String method, Object... params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", ids.getAndIncrement());
        req.put("method", method);
        ArrayNode arr = req.putArray("params");
        for (Object p : params) {
            if (p instanceof JsonNode n) {
                arr.add(n);
            } else {
                arr.add(String.valueOf(p));
            }
        }

        String url = appProperties.getPayments().getEthRpcUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("app.payments.eth-rpc-url is not configured");
        }
        String body;
        try {
            body = http.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(req))
                    .retrieve()
                    .body(String.class);
            JsonNode resp = mapper.readTree(body);
            if (resp.has("error") && !resp.get("error").isNull()) {
                throw new IllegalStateException("JSON-RPC error from " + method + ": " + resp.get("error"));
            }
            return resp.get("result");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to (de)serialize JSON-RPC " + method, e);
        }
    }

    /** Methods this client supports, for documentation/health checks. */
    public List<String> supportedMethods() {
        return List.of("eth_chainId", "eth_blockNumber", "eth_gasPrice", "eth_getTransactionCount",
                "eth_getBalance", "eth_sendRawTransaction", "eth_call", "eth_getTransactionReceipt");
    }
}
