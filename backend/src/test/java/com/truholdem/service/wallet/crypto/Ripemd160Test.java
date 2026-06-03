package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RIPEMD-160")
class Ripemd160Test {

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    @Test
    @DisplayName("matches the canonical empty-string and \"abc\" vectors")
    void canonicalVectors() {
        assertThat(hex(Ripemd160.digest(new byte[0])))
                .isEqualTo("9c1185a5c5e9fc54612808977ee8f548b2258d31");
        assertThat(hex(Ripemd160.digest("abc".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc");
    }

    @Test
    @DisplayName("matches the \"message digest\" vector (multi-word)")
    void messageDigestVector() {
        assertThat(hex(Ripemd160.digest("message digest".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("5d0689ef49d2fae572b881b123a85ffa21595f36");
    }
}
