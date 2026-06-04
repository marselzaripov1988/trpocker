package com.truholdem.service.wallet.btc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.AppProperties;

/**
 * Minimal Bitcoin Core JSON-RPC client (pure {@code RestClient} + Jackson + HTTP-Basic auth, no bitcoinj).
 * Scans the UTXO set for an address ({@code scantxoutset} — no wallet import needed), broadcasts a raw tx, and
 * reads confirmations. Active when {@code app.payments.btc-rpc-enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.payments.btc-rpc-enabled", havingValue = "true")
public class BtcRpcClient {

    /** A spendable output of the scanned address. */
    public record Utxo(String txid, long vout, long valueSat, String scriptPubKeyHex, long confirmations) {
    }

    private static final BigDecimal SATS_PER_BTC = BigDecimal.valueOf(100_000_000L);

    private final AppProperties appProperties;
    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public BtcRpcClient(AppProperties appProperties) {
        this.appProperties = appProperties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000); // scantxoutset can take a while on large chains
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Unspent outputs paying {@code address} (via {@code scantxoutset}); confirmations computed from heights. */
    public List<Utxo> listUnspent(String address) {
        JsonNode result = rpc("scantxoutset", "\"start\",[{\"desc\":\"addr(" + address + ")\"}]");
        long chainHeight = result.path("height").asLong();
        List<Utxo> utxos = new ArrayList<>();
        for (JsonNode u : result.path("unspents")) {
            long valueSat = u.get("amount").decimalValue().multiply(SATS_PER_BTC).longValueExact();
            long utxoHeight = u.path("height").asLong();
            long confirmations = utxoHeight > 0 ? chainHeight - utxoHeight + 1 : 0;
            utxos.add(new Utxo(u.get("txid").asText(), u.get("vout").asLong(), valueSat,
                    u.get("scriptPubKey").asText(), confirmations));
        }
        return utxos;
    }

    /** Broadcast a raw transaction; returns its txid. */
    public String sendRawTransaction(String rawTxHex) {
        return rpc("sendrawtransaction", "\"" + rawTxHex + "\"").asText();
    }

    /** Confirmations of a transaction (0 if in the mempool / unknown). Requires {@code -txindex}. */
    public long confirmations(String txid) {
        JsonNode tx = rpc("getrawtransaction", "\"" + txid + "\",true");
        return tx.path("confirmations").asLong(0);
    }

    public long blockCount() {
        return rpc("getblockcount", "").asLong();
    }

    /** Issue a JSON-RPC call (params already JSON-encoded) and return the {@code result}; throws on error. */
    private JsonNode rpc(String method, String paramsJson) {
        AppProperties.Payments p = appProperties.getPayments();
        String url = p.getBtcRpcUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("app.payments.btc-rpc-url is not configured");
        }
        String body = "{\"jsonrpc\":\"1.0\",\"id\":\"truholdem\",\"method\":\"" + method
                + "\",\"params\":[" + paramsJson + "]}";
        String auth = Base64.getEncoder().encodeToString(
                (p.getBtcRpcUser() + ":" + p.getBtcRpcPassword()).getBytes(StandardCharsets.UTF_8));
        try {
            String resp = http.post().uri(url)
                    .header("Authorization", "Basic " + auth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = mapper.readTree(resp);
            if (root.has("error") && !root.get("error").isNull()) {
                throw new IllegalStateException("Bitcoin RPC error from " + method + ": " + root.get("error"));
            }
            return root.get("result");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Bitcoin RPC response for " + method, e);
        }
    }
}
