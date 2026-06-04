package com.truholdem.service.wallet.btc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BtcScript (network hrp + P2WPKH scriptPubKey + txid reversal)")
class BtcScriptTest {

    @Test
    @DisplayName("network → bech32 hrp")
    void hrp() {
        assertThat(BtcScript.hrp("mainnet")).isEqualTo("bc");
        assertThat(BtcScript.hrp("testnet")).isEqualTo("tb");
        assertThat(BtcScript.hrp("regtest")).isEqualTo("bcrt");
        assertThatThrownBy(() -> BtcScript.hrp("dogenet")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("P2WPKH scriptPubKey is OP_0 <20-byte program> for the BIP-173 canonical address")
    void scriptPubKey() {
        // BIP-173 canonical mainnet P2WPKH (witness program = 0x751e76...28decf2c)
        byte[] script = BtcScript.p2wpkhScriptPubKey("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", "mainnet");
        assertThat(script).hasSize(22);
        assertThat(BtcScript.toHex(script))
                .isEqualTo("0014751e76e8199196d454941c45d1b3a323f1433bd6");
    }

    @Test
    @DisplayName("a non-P2WPKH address is rejected")
    void rejectsNonP2wpkh() {
        assertThatThrownBy(() -> BtcScript.p2wpkhScriptPubKey("not-an-address", "regtest"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("txid is reversed into internal little-endian byte order")
    void txidReversal() {
        byte[] le = BtcScript.txidToInternalBytes("00112233aabbccdd");
        assertThat(BtcScript.toHex(le)).isEqualTo("ddccbbaa33221100");
    }
}
