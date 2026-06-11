package com.truholdem.service.wallet.sol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Solana compact-u16 (shortvec) length encoding against the canonical boundary vectors. */
@DisplayName("SolShortVec — compact-u16 length encoding")
class SolShortVecTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    @DisplayName("encodes canonical 1/2/3-byte boundaries")
    void encodesBoundaries() {
        assertThat(HEX.formatHex(SolShortVec.encodeLength(0))).isEqualTo("00");
        assertThat(HEX.formatHex(SolShortVec.encodeLength(1))).isEqualTo("01");
        assertThat(HEX.formatHex(SolShortVec.encodeLength(127))).isEqualTo("7f");
        assertThat(HEX.formatHex(SolShortVec.encodeLength(128))).isEqualTo("8001");
        assertThat(HEX.formatHex(SolShortVec.encodeLength(255))).isEqualTo("ff01");
        assertThat(HEX.formatHex(SolShortVec.encodeLength(256))).isEqualTo("8002");
        assertThat(HEX.formatHex(SolShortVec.encodeLength(16383))).isEqualTo("ff7f");
        assertThat(HEX.formatHex(SolShortVec.encodeLength(16384))).isEqualTo("808001");
        assertThat(HEX.formatHex(SolShortVec.encodeLength(65535))).isEqualTo("ffff03");
    }
}
