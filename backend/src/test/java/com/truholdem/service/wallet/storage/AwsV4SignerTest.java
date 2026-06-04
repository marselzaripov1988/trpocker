package com.truholdem.service.wallet.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AWS SigV4 signer")
class AwsV4SignerTest {

    @Test
    @DisplayName("reproduces the official AWS SigV4 'get-vanilla' test vector")
    void getVanillaVector() {
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", "example.amazonaws.com");
        headers.put("x-amz-date", "20150830T123600Z");

        String auth = AwsV4Signer.authorizationHeader("GET", "/", "", headers,
                AwsV4Signer.sha256Hex(new byte[0]),
                "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "us-east-1", "service",
                "20150830T123600Z");

        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 "
                + "Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                + "SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }
}
