package com.truholdem.service.wallet.eth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.truholdem.config.AppProperties;

@DisplayName("EthRpcClient (JSON-RPC envelope against a fake node)")
class EthRpcClientTest {

    /** A fake JSON-RPC node: replies to each method with a canned result; captures the last request. */
    private static final class FakeNode implements AutoCloseable {
        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();
        private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();

        FakeNode() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                JsonNode req = mapper.readTree(exchange.getRequestBody().readAllBytes());
                lastRequest.set(req);
                String method = req.get("method").asText();
                String result = switch (method) {
                    case "eth_chainId" -> "\"0x539\""; // 1337
                    case "eth_gasPrice" -> "\"0x3b9aca00\""; // 1 gwei
                    case "eth_getTransactionCount" -> "\"0x5\"";
                    case "eth_sendRawTransaction" -> "\"0xdeadbeef\"";
                    case "eth_getTransactionReceipt" -> req.get("params").get(0).asText().equals("0xpending")
                            ? "null"
                            : "{\"transactionHash\":\"0xabc\",\"blockNumber\":\"0x10\",\"status\":\"0x1\"}";
                    default -> "null";
                };
                String body = "{\"jsonrpc\":\"2.0\",\"id\":" + req.get("id") + ",\"result\":" + result + "}";
                byte[] out = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** A fake node that always returns a JSON-RPC error object. */
    private static HttpServer errorNode() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,\"message\":\"nope\"}}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return server;
    }

    private FakeNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.close();
        }
    }

    private EthRpcClient clientFor(String url) {
        AppProperties props = new AppProperties();
        props.getPayments().setEthRpcUrl(url);
        return new EthRpcClient(props);
    }

    @Test
    @DisplayName("parses hex quantities and the well-formed JSON-RPC envelope")
    void parsesQuantities() throws Exception {
        node = new FakeNode();
        EthRpcClient client = clientFor(node.url());

        assertThat(client.chainId()).isEqualTo(1337L);
        assertThat(client.gasPrice()).isEqualTo(BigInteger.valueOf(1_000_000_000L));
        assertThat(client.pendingNonce("0xabc")).isEqualTo(BigInteger.valueOf(5));
        assertThat(client.sendRawTransaction("0xf86b...")).isEqualTo("0xdeadbeef");

        assertThat(node.lastRequest.get().get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(node.lastRequest.get().get("method").asText()).isEqualTo("eth_sendRawTransaction");
    }

    @Test
    @DisplayName("receipt: present (mined+success) and absent (pending → empty)")
    void receipts() throws Exception {
        node = new FakeNode();
        EthRpcClient client = clientFor(node.url());

        var mined = client.receipt("0xmined").orElseThrow();
        assertThat(mined.success()).isTrue();
        assertThat(mined.blockNumber()).isEqualTo(BigInteger.valueOf(16));

        assertThat(client.receipt("0xpending")).isEmpty();
    }

    @Test
    @DisplayName("a JSON-RPC error result is surfaced as an exception")
    void errorSurfaces() throws Exception {
        HttpServer server = errorNode();
        try {
            EthRpcClient client = clientFor("http://localhost:" + server.getAddress().getPort());
            assertThatThrownBy(client::gasPrice)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JSON-RPC error");
        } finally {
            server.stop(0);
        }
    }
}
