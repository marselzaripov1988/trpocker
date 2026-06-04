package com.truholdem.service.wallet;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.AppProperties;
import com.truholdem.service.wallet.storage.AwsV4Signer;

/**
 * KYC key provider backed by <b>AWS KMS envelope encryption</b>. Each upload gets a fresh AES-256 data key
 * minted by KMS ({@code GenerateDataKey}); the plaintext key encrypts the video (AES-GCM via
 * {@link com.truholdem.service.wallet.crypto.KycCrypto}) and is then discarded, while the CMK-wrapped data key
 * is recorded as the document's key id ({@code "kms:" + base64(CiphertextBlob)}). Reads unwrap it with
 * {@code Decrypt}. The raw key never lives in config — only the CMK id + IAM credentials do.
 *
 * <p>Talks to the KMS JSON API directly over a {@link RestClient}, signed with {@link AwsV4Signer} (SigV4,
 * service {@code kms}) — no AWS SDK, so the offline build adds no dependency. Active when
 * {@code app.payments.kyc-key-provider=kms}.
 *
 * <p><b>Migration note:</b> this provider only resolves {@code kms:}-scheme key ids. Switching an environment
 * that already has config-keyring-encrypted documents over to KMS leaves those older documents unreadable —
 * re-encrypt them (or keep the config provider) before flipping the switch.
 */
@Component
@ConditionalOnProperty(name = "app.payments.kyc-key-provider", havingValue = "kms")
public class KmsKycKeyProvider implements KycKeyProvider {

    /** Key-id scheme prefix marking a KMS-wrapped data key (vs. a config keyring id). */
    public static final String SCHEME = "kms:";

    private static final String SERVICE = "kms";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final AppProperties appProperties;
    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public KmsKycKeyProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<DataKey> newDataKey() {
        AppProperties.Payments p = appProperties.getPayments();
        if (p.getKmsKeyId() == null || p.getKmsKeyId().isBlank()) {
            throw new IllegalStateException("app.payments.kms-key-id is required when kyc-key-provider=kms");
        }
        Map<String, Object> resp = call("TrentService.GenerateDataKey",
                new LinkedHashMap<>(Map.of("KeyId", p.getKmsKeyId(), "KeySpec", "AES_256")));
        byte[] plaintext = Base64.getDecoder().decode((String) resp.get("Plaintext"));
        String wrapped = (String) resp.get("CiphertextBlob"); // already base64 in the KMS JSON response
        return Optional.of(new DataKey(plaintext, SCHEME + wrapped));
    }

    @Override
    public byte[] resolveKey(String keyId) {
        if (keyId == null || !keyId.startsWith(SCHEME)) {
            throw new IllegalStateException("KMS key provider cannot resolve non-KMS key id '" + keyId
                    + "' (re-encrypt config-keyring documents before switching to KMS)");
        }
        String wrapped = keyId.substring(SCHEME.length());
        Map<String, Object> resp = call("TrentService.Decrypt",
                new LinkedHashMap<>(Map.of("CiphertextBlob", wrapped)));
        return Base64.getDecoder().decode((String) resp.get("Plaintext"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String target, Map<String, Object> body) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize KMS request", e);
        }
        URI uri = endpoint();
        String amzDate = AMZ_DATE.format(Instant.now());
        String payloadHash = AwsV4Signer.sha256Hex(payload);

        TreeMap<String, String> signed = new TreeMap<>();
        signed.put("host", hostHeader(uri));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);
        signed.put("x-amz-target", target);

        AppProperties.Payments p = appProperties.getPayments();
        String auth = AwsV4Signer.authorizationHeader("POST", rawPath(uri), "", signed, payloadHash,
                p.getKmsAccessKey(), p.getKmsSecretKey(), p.getKmsRegion(), SERVICE, amzDate);

        // Read the body as String and parse it ourselves: KMS replies with x-amz-json-1.1, which the default
        // Jackson message converter does not register, so .body(Map.class) would fail to find a converter.
        String json = http.post().uri(uri)
                .contentType(MediaType.valueOf(JSON_1_1))
                .header("X-Amz-Target", target)
                .header("X-Amz-Date", amzDate)
                .header("X-Amz-Content-Sha256", payloadHash)
                .header("Authorization", auth)
                .body(payload)
                .retrieve()
                .body(String.class);
        try {
            return mapper.readValue(json, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse KMS response", e);
        }
    }

    private URI endpoint() {
        String e = appProperties.getPayments().getKmsEndpoint();
        if (e == null || e.isBlank()) {
            e = "https://kms." + appProperties.getPayments().getKmsRegion() + ".amazonaws.com";
        }
        return URI.create(e.endsWith("/") ? e : e + "/");
    }

    private static String rawPath(URI uri) {
        String path = uri.getRawPath();
        return (path == null || path.isEmpty()) ? "/" : path;
    }

    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }
}
