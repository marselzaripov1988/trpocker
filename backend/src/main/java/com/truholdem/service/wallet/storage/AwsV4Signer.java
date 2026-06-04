package com.truholdem.service.wallet.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.SortedMap;
import java.util.StringJoiner;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 signing (HMAC-SHA256) in pure Java — lets the wallet talk to S3/MinIO over the
 * RestClient without the AWS SDK (the offline build adds no new dependency). Verified against the official
 * AWS SigV4 test-suite vector.
 */
public final class AwsV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private AwsV4Signer() {
    }

    /** SHA-256 hex of a payload (the S3 {@code x-amz-content-sha256} value). */
    public static String sha256Hex(byte[] data) {
        return hex(sha256(data));
    }

    /**
     * Build the {@code Authorization} header value. {@code signedHeaders} must be the exact (lowercased name →
     * value) set sent with the request, including {@code host}; {@code amzDate} is {@code yyyyMMdd'T'HHmmss'Z'}.
     */
    public static String authorizationHeader(String method, String canonicalUri, String canonicalQuery,
            SortedMap<String, String> signedHeaders, String payloadSha256Hex,
            String accessKey, String secretKey, String region, String service, String amzDate) {
        String dateStamp = amzDate.substring(0, 8);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringJoiner signedHeaderNames = new StringJoiner(";");
        for (var e : signedHeaders.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue().trim()).append('\n');
            signedHeaderNames.add(e.getKey());
        }
        String signedHeaderList = signedHeaderNames.toString();

        String canonicalRequest = String.join("\n", method, canonicalUri, canonicalQuery,
                canonicalHeaders.toString(), signedHeaderList, payloadSha256Hex);

        String scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = String.join("\n", ALGORITHM, amzDate, scope, hex(sha256(utf8(canonicalRequest))));

        byte[] kDate = hmac(utf8("AWS4" + secretKey), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        return ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaderList + ", Signature=" + signature;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(utf8(data));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        }
        return sb.toString();
    }
}
