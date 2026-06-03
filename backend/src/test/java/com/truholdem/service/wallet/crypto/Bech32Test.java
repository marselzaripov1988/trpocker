package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bech32 (SegWit v0) encoding")
class Bech32Test {

    @Test
    @DisplayName("encodes the canonical BIP-173 P2WPKH vector")
    void bip173Vector() {
        byte[] program = HexFormat.of().parseHex("751e76e8199196d454941c45d1b3a323f1433bd6");
        assertThat(Bech32.encodeSegwit("bc", 0, program))
                .isEqualTo("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4");
    }

    @Test
    @DisplayName("encode then decode round-trips the witness program")
    void roundTrip() {
        byte[] program = HexFormat.of().parseHex("751e76e8199196d454941c45d1b3a323f1433bd6");
        String address = Bech32.encodeSegwit("bc", 0, program);
        assertThat(Bech32.decodeP2wpkh("bc", address)).isEqualTo(program);
    }

    @Test
    @DisplayName("bech32m (witness v1) encode/decode round-trips a 32-byte program")
    void bech32mRoundTrip() {
        byte[] program = HexFormat.of()
                .parseHex("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
        String address = Bech32.encodeSegwit("bc", 1, program);
        assertThat(address).startsWith("bc1p");
        assertThat(Bech32.decodeP2tr("bc", address)).isEqualTo(program);
        // A v1 address must not validate as v0 (different checksum constant).
        assertThat(Bech32.decodeP2wpkh("bc", address)).isNull();
    }

    @Test
    @DisplayName("rejects wrong hrp, tampered checksum, mixed case and garbage")
    void rejectsInvalid() {
        String valid = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
        assertThat(Bech32.decodeP2wpkh("tb", valid)).as("wrong hrp").isNull();
        assertThat(Bech32.decodeP2wpkh("bc", valid.substring(0, valid.length() - 1) + "0"))
                .as("tampered checksum").isNull();
        assertThat(Bech32.decodeP2wpkh("bc", "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5Xw7kv8f3t4"))
                .as("mixed case").isNull();
        assertThat(Bech32.decodeP2wpkh("bc", "notanaddress")).isNull();
        assertThat(Bech32.decodeP2wpkh("bc", null)).isNull();
    }
}
