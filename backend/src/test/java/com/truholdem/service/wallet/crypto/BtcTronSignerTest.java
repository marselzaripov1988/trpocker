package com.truholdem.service.wallet.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the offline BTC (P2WPKH / BIP-143) and TRON signers against ground-truth vectors produced by
 * independent implementations (embit for Bitcoin, eth-account for the secp256k1 signature).
 */
@DisplayName("Offline BTC (BIP-143) + TRON signers")
class BtcTronSignerTest {

    private static byte[] hex(String h) {
        return java.util.HexFormat.of().parseHex(h);
    }

    private static String hex(byte[] b) {
        return java.util.HexFormat.of().formatHex(b);
    }

    // --- Bitcoin P2WPKH (BIP-143), vector from embit ---------------------------------------------

    private static final BigInteger BTC_PK =
            new BigInteger("9b10a57a872ce8547c58a84ca51f99ef3df169c10475ddc1ac5bb748e603059c", 16);

    private static List<BtcSigner.TxIn> btcInputs() {
        return List.of(new BtcSigner.TxIn(hex("aa".repeat(32)), 0, 0xffffffffL));
    }

    private static List<BtcSigner.TxOut> btcOutputs() {
        return List.of(new BtcSigner.TxOut(90_000, hex("0014" + "00".repeat(20)))); // P2WPKH OP_0 <20>
    }

    @Test
    @DisplayName("P2WPKH scriptCode matches the key's HASH160")
    void scriptCode() {
        assertThat(hex(BtcSigner.p2wpkhScriptCode(BTC_PK)))
                .isEqualTo("76a9146877f98a300e8f0a24fc00c826e74380d76ef86b88ac");
    }

    @Test
    @DisplayName("BIP-143 sighash matches embit")
    void bip143Sighash() {
        byte[] sighash = BtcSigner.bip143SighashP2wpkh(2, btcInputs(), btcOutputs(), 0,
                BtcSigner.p2wpkhScriptCode(BTC_PK), 100_000, 0);
        assertThat(hex(sighash)).isEqualTo("6d323dc4b4488f1040910f56e1903148f640f6b7efd70d1f577a8bd5937f493e");
    }

    @Test
    @DisplayName("P2WPKH witness signature is a valid DER ECDSA sig over the BIP-143 sighash")
    void signP2wpkh() {
        // The sighash is verified against embit (above); the signature here is cross-checked byte-for-byte
        // against eth-account signing that same sighash. (A different RFC-6979 variant — e.g. embit's — would
        // produce a different but equally valid signature; the network accepts any valid low-s signature.)
        byte[] sig = BtcSigner.signP2wpkhInput(BTC_PK, 2, btcInputs(), btcOutputs(), 0, 100_000, 0);
        assertThat(hex(sig)).isEqualTo("3045022100b166fb80b473967e6e3c74419b10e7fa3b961c99f4ea798c12dfad71"
                + "98295c5b02204ca93b7353c74b8695c3e1cd5944af056051924428f9f063407848352a55d5ee01");
    }

    // --- TRON, vector from eth-account signing SHA-256(raw_data) ----------------------------------

    @Test
    @DisplayName("TRON txId = SHA-256(raw_data) and the 65-byte recoverable signature match")
    void tronSign() {
        byte[] rawData = hex("0a0212340102030405");
        assertThat(hex(TronSigner.txId(rawData)))
                .isEqualTo("6bd3a4811150337635a202027dfb51969e84325e7f74e3306c9c443bec67aa95");
        byte[] sig = TronSigner.sign(rawData, BigInteger.ONE);
        assertThat(hex(sig)).isEqualTo(
                "ec147bc34e62578f2daf00153f27e80df004b11d35bff7e7e9bfc7f3ccc2b6a3"
                        + "28b06f65b7eac8dd46bbbba36ffdeec463ee72a376255eb9d1b29f3227cacae2" + "00");
    }
}
