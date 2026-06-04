package com.truholdem.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.truholdem.config.AppProperties;
import com.truholdem.service.wallet.KycKeyProvider.DataKey;
import com.truholdem.service.wallet.crypto.KycCrypto;

@DisplayName("KmsKycKeyProvider (AWS KMS envelope encryption against a fake KMS)")
class KmsKycKeyProviderTest {

    /**
     * A minimal fake KMS: GenerateDataKey mints a random 32-byte key and returns it plus a reversibly-wrapped
     * CiphertextBlob (XOR mask, so the wrapped form differs from the plaintext); Decrypt reverses the wrap.
     */
    private static final class FakeKms implements AutoCloseable {
        private static final byte MASK = 0x5A;
        private final HttpServer server;
        private final ObjectMapper mapper = new ObjectMapper();
        private final AtomicReference<String> lastAuth = new AtomicReference<>();
        private final AtomicReference<String> lastTarget = new AtomicReference<>();
        private int counter;

        FakeKms() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    String target = exchange.getRequestHeaders().getFirst("X-Amz-Target");
                    lastTarget.set(target);
                    lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    Map<String, Object> req = readJson(exchange.getRequestBody());

                    Map<String, Object> resp = new ConcurrentHashMap<>();
                    if (target != null && target.endsWith("GenerateDataKey")) {
                        byte[] key = new byte[32];
                        for (int i = 0; i < key.length; i++) {
                            key[i] = (byte) (counter * 31 + i); // deterministic, no Math.random
                        }
                        counter++;
                        resp.put("Plaintext", Base64.getEncoder().encodeToString(key));
                        resp.put("CiphertextBlob", Base64.getEncoder().encodeToString(wrap(key)));
                        resp.put("KeyId", "arn:aws:kms:test:0:key/fake");
                    } else if (target != null && target.endsWith("Decrypt")) {
                        byte[] blob = Base64.getDecoder().decode((String) req.get("CiphertextBlob"));
                        resp.put("Plaintext", Base64.getEncoder().encodeToString(wrap(blob))); // XOR is its own inverse
                        resp.put("KeyId", "arn:aws:kms:test:0:key/fake");
                    } else {
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    byte[] out = mapper.writeValueAsBytes(resp);
                    exchange.getResponseHeaders().add("Content-Type", "application/x-amz-json-1.1");
                    exchange.sendResponseHeaders(200, out.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(out);
                    }
                } catch (RuntimeException e) {
                    exchange.sendResponseHeaders(500, -1);
                }
            });
            server.start();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> readJson(InputStream in) throws IOException {
            byte[] body = in.readAllBytes();
            if (body.length == 0) {
                return Map.of();
            }
            return mapper.readValue(new String(body, StandardCharsets.UTF_8), Map.class);
        }

        private static byte[] wrap(byte[] data) {
            byte[] out = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                out[i] = (byte) (data[i] ^ MASK);
            }
            return out;
        }

        String endpoint() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private FakeKms kms;

    @AfterEach
    void tearDown() {
        if (kms != null) {
            kms.close();
        }
    }

    private AppProperties propsFor(FakeKms fake) {
        AppProperties props = new AppProperties();
        AppProperties.Payments p = props.getPayments();
        p.setKycKeyProvider("kms");
        p.setKmsEndpoint(fake.endpoint());
        p.setKmsRegion("us-east-1");
        p.setKmsKeyId("arn:aws:kms:test:0:key/fake");
        p.setKmsAccessKey("AKIDEXAMPLE");
        p.setKmsSecretKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
        return props;
    }

    @Test
    @DisplayName("newDataKey mints a kms:-scheme key id and the wrapped blob round-trips via Decrypt")
    void envelopeRoundTrip() throws Exception {
        kms = new FakeKms();
        KmsKycKeyProvider provider = new KmsKycKeyProvider(propsFor(kms));

        DataKey dataKey = provider.newDataKey().orElseThrow();
        assertThat(dataKey.key()).hasSize(32);
        assertThat(dataKey.keyId()).startsWith(KmsKycKeyProvider.SCHEME);

        byte[] resolved = provider.resolveKey(dataKey.keyId());
        assertThat(resolved).as("Decrypt unwraps to the same data key").isEqualTo(dataKey.key());

        byte[] video = "kyc passport video".getBytes(StandardCharsets.UTF_8);
        byte[] blob = KycCrypto.encrypt(video, dataKey.key());
        assertThat(KycCrypto.decrypt(blob, provider.resolveKey(dataKey.keyId()))).isEqualTo(video);
    }

    @Test
    @DisplayName("requests are SigV4-signed and carry the KMS X-Amz-Target")
    void signsRequests() throws Exception {
        kms = new FakeKms();
        KmsKycKeyProvider provider = new KmsKycKeyProvider(propsFor(kms));

        provider.newDataKey();

        assertThat(kms.lastTarget.get()).isEqualTo("TrentService.GenerateDataKey");
        assertThat(kms.lastAuth.get())
                .contains("AWS4-HMAC-SHA256")
                .contains("Credential=AKIDEXAMPLE/")
                .contains("/us-east-1/kms/aws4_request")
                .contains("x-amz-target");
    }

    @Test
    @DisplayName("a non-KMS key id is rejected (cannot resolve config-keyring ids)")
    void rejectsNonKmsKeyId() throws Exception {
        kms = new FakeKms();
        KmsKycKeyProvider provider = new KmsKycKeyProvider(propsFor(kms));

        assertThatThrownBy(() -> provider.resolveKey("2026q2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-KMS");
    }

    @Test
    @DisplayName("newDataKey requires a configured CMK id")
    void requiresKeyId() throws Exception {
        kms = new FakeKms();
        AppProperties props = propsFor(kms);
        props.getPayments().setKmsKeyId("");
        KmsKycKeyProvider provider = new KmsKycKeyProvider(props);

        assertThatThrownBy(provider::newDataKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kms-key-id");
    }
}
