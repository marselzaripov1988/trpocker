package com.truholdem.service.wallet.sol;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit-tests the Solana JSON-RPC request builder + response parsers against sample payloads with the real
 * Solana response shapes. Live calls are exercised end-to-end against {@code solana-test-validator} in slice 5.
 */
@DisplayName("SolanaRpcClient — request/response parsing")
class SolanaRpcClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("request body is well-formed JSON-RPC 2.0")
    void requestBodyIsWellFormed() {
        String body = SolanaRpcClient.requestBody(MAPPER, "getLatestBlockhash",
                List.of(Map.of("commitment", "confirmed")));
        JsonNode node = read(body);
        assertThat(node.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(node.get("id").asInt()).isEqualTo(1);
        assertThat(node.get("method").asText()).isEqualTo("getLatestBlockhash");
        assertThat(node.get("params")).hasSize(1);
        assertThat(node.get("params").get(0).get("commitment").asText()).isEqualTo("confirmed");
    }

    @Test
    @DisplayName("parses getLatestBlockhash")
    void parsesBlockhash() {
        JsonNode result = read("{\"context\":{\"slot\":123},"
                + "\"value\":{\"blockhash\":\"EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N\","
                + "\"lastValidBlockHeight\":18446}}");
        SolanaRpcClient.Blockhash bh = SolanaRpcClient.parseBlockhash(result);
        assertThat(bh.blockhash()).isEqualTo("EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N");
        assertThat(bh.lastValidBlockHeight()).isEqualTo(18446L);
    }

    @Test
    @DisplayName("parses getTokenAccountBalance (raw base units)")
    void parsesTokenAmount() {
        JsonNode result = read("{\"context\":{\"slot\":1},"
                + "\"value\":{\"amount\":\"1000000\",\"decimals\":6,\"uiAmountString\":\"1\"}}");
        assertThat(SolanaRpcClient.parseTokenAmount(result)).isEqualTo(new BigInteger("1000000"));
    }

    @Test
    @DisplayName("parses getTokenAccountsByOwner")
    void parsesTokenAccounts() {
        JsonNode result = read("{\"context\":{\"slot\":1},\"value\":[{"
                + "\"pubkey\":\"ATA111111111111111111111111111111111111111\","
                + "\"account\":{\"data\":{\"parsed\":{\"info\":{\"tokenAmount\":{\"amount\":\"500000\","
                + "\"decimals\":6}}}}}}]}");
        List<SolanaRpcClient.TokenAccount> accts = SolanaRpcClient.parseTokenAccounts(result);
        assertThat(accts).hasSize(1);
        assertThat(accts.get(0).pubkey()).isEqualTo("ATA111111111111111111111111111111111111111");
        assertThat(accts.get(0).amount()).isEqualTo(new BigInteger("500000"));
    }

    @Test
    @DisplayName("parses getMultipleAccounts balances by address, skipping null (uninitialized) entries")
    void parsesMultipleTokenAmounts() {
        // value is positional/parallel to the requested addresses; the middle account is uninitialized (null).
        JsonNode result = read("{\"context\":{\"slot\":1},\"value\":["
                + "{\"data\":{\"parsed\":{\"info\":{\"tokenAmount\":{\"amount\":\"5000000\",\"decimals\":6}}}}},"
                + "null,"
                + "{\"data\":{\"parsed\":{\"info\":{\"tokenAmount\":{\"amount\":\"7\",\"decimals\":6}}}}}]}");
        Map<String, BigInteger> balances =
                SolanaRpcClient.parseMultipleTokenAmounts(result, List.of("AtaA", "AtaB", "AtaC"));
        assertThat(balances).hasSize(2);
        assertThat(balances.get("AtaA")).isEqualTo(new BigInteger("5000000"));
        assertThat(balances).doesNotContainKey("AtaB"); // null account omitted
        assertThat(balances.get("AtaC")).isEqualTo(new BigInteger("7"));
    }

    @Test
    @DisplayName("parses signature status: confirmed, finalized (null confirmations), failed, and not-found")
    void parsesSignatureStatus() {
        SolanaRpcClient.SignatureStatus confirmed = SolanaRpcClient.parseSignatureStatus(read(
                "{\"value\":[{\"slot\":1,\"confirmations\":10,\"err\":null,"
                + "\"confirmationStatus\":\"confirmed\"}]}"));
        assertThat(confirmed.found()).isTrue();
        assertThat(confirmed.confirmations()).isEqualTo(10L);
        assertThat(confirmed.confirmationStatus()).isEqualTo("confirmed");
        assertThat(confirmed.failed()).isFalse();

        SolanaRpcClient.SignatureStatus finalized = SolanaRpcClient.parseSignatureStatus(read(
                "{\"value\":[{\"slot\":1,\"confirmations\":null,\"err\":null,"
                + "\"confirmationStatus\":\"finalized\"}]}"));
        assertThat(finalized.confirmations()).isEqualTo(-1L); // null == finalized
        assertThat(finalized.confirmationStatus()).isEqualTo("finalized");

        SolanaRpcClient.SignatureStatus failed = SolanaRpcClient.parseSignatureStatus(read(
                "{\"value\":[{\"slot\":1,\"confirmations\":5,\"err\":{\"InstructionError\":[0,{}]},"
                + "\"confirmationStatus\":\"confirmed\"}]}"));
        assertThat(failed.failed()).isTrue();

        SolanaRpcClient.SignatureStatus notFound = SolanaRpcClient.parseSignatureStatus(read(
                "{\"value\":[null]}"));
        assertThat(notFound.found()).isFalse();
    }
}
