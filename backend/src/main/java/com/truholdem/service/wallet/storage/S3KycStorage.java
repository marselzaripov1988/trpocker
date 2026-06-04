package com.truholdem.service.wallet.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.truholdem.config.AppProperties;

/**
 * KYC media in S3-compatible object storage (AWS S3 / MinIO), path-style, signed with AWS SigV4
 * ({@link AwsV4Signer}) over the RestClient — no AWS SDK. Active when {@code app.payments.kyc-storage-type=s3}.
 * The bucket is created lazily on first use (idempotent). Cluster-friendly (no shared filesystem needed).
 */
@Component
@ConditionalOnProperty(name = "app.payments.kyc-storage-type", havingValue = "s3")
public class S3KycStorage implements KycStorage {

    private static final Logger log = LoggerFactory.getLogger(S3KycStorage.class);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String EMPTY_SHA256 = AwsV4Signer.sha256Hex(new byte[0]);
    private static final String SERVICE = "s3";

    private final AppProperties appProperties;
    private final RestClient http;
    private volatile boolean bucketEnsured;

    public S3KycStorage(AppProperties appProperties) {
        this.appProperties = appProperties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(60000); // large KYC videos
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public void store(String key, byte[] content) {
        ensureBucket();
        String payloadHash = AwsV4Signer.sha256Hex(content);
        URI uri = objectUri(key);
        http.put().uri(uri)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .headers(h -> sign("PUT", uri, payloadHash).forEach(h::add))
                .body(content)
                .retrieve().toBodilessEntity();
    }

    @Override
    public byte[] load(String key) {
        URI uri = objectUri(key);
        return http.get().uri(uri)
                .headers(h -> sign("GET", uri, EMPTY_SHA256).forEach(h::add))
                .retrieve().body(byte[].class);
    }

    @Override
    public void delete(String key) {
        URI uri = objectUri(key);
        http.method(org.springframework.http.HttpMethod.DELETE).uri(uri)
                .headers(h -> sign("DELETE", uri, EMPTY_SHA256).forEach(h::add))
                .retrieve().toBodilessEntity();
    }

    private void ensureBucket() {
        if (bucketEnsured) {
            return;
        }
        URI uri = URI.create(endpoint() + "/" + bucket());
        byte[] body = ("<CreateBucketConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                + "<LocationConstraint>" + appProperties.getPayments().getS3Region()
                + "</LocationConstraint></CreateBucketConfiguration>").getBytes(StandardCharsets.UTF_8);
        String payloadHash = AwsV4Signer.sha256Hex(body);
        try {
            http.put().uri(uri)
                    .contentType(MediaType.APPLICATION_XML)
                    .headers(h -> sign("PUT", uri, payloadHash).forEach(h::add))
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            log.debug("Bucket {} already exists — continuing", bucket()); // BucketAlreadyOwnedByYou
        }
        bucketEnsured = true;
    }

    /** Compute the SigV4 headers to add (x-amz-date, x-amz-content-sha256, Authorization); Host is implicit. */
    private java.util.Map<String, String> sign(String method, URI uri, String payloadHash) {
        String amzDate = AMZ_DATE.format(Instant.now());
        TreeMap<String, String> signed = new TreeMap<>();
        signed.put("host", hostHeader(uri));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", amzDate);

        AppProperties.Payments p = appProperties.getPayments();
        String auth = AwsV4Signer.authorizationHeader(method, uri.getRawPath(), "", signed, payloadHash,
                p.getS3AccessKey(), p.getS3SecretKey(), p.getS3Region(), SERVICE, amzDate);

        return java.util.Map.of(
                "x-amz-date", amzDate,
                "x-amz-content-sha256", payloadHash,
                "Authorization", auth);
    }

    private URI objectUri(String key) {
        return URI.create(endpoint() + "/" + bucket() + "/" + encode(key));
    }

    private String endpoint() {
        String e = appProperties.getPayments().getS3Endpoint();
        if (e == null || e.isBlank()) {
            throw new IllegalStateException("app.payments.s3-endpoint is not configured");
        }
        return e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
    }

    private String bucket() {
        return appProperties.getPayments().getS3Bucket();
    }

    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        return defaultPort ? uri.getHost() : uri.getHost() + ":" + port;
    }

    /** Percent-encode a path segment, leaving RFC-3986 unreserved characters (enough for UUID keys). */
    private static String encode(String segment) {
        StringBuilder sb = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(Character.forDigit((b >> 4) & 0xf, 16))
                        .append(Character.forDigit(b & 0xf, 16));
            }
        }
        return sb.toString();
    }
}
