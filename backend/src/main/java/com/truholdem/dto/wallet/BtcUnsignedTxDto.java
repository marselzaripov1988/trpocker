package com.truholdem.dto.wallet;

import java.util.List;
import java.util.UUID;

/**
 * An unsigned BTC (P2WPKH) transaction assembled from live node state, for the air-gapped signer: selected
 * UTXO inputs (with prevout amounts), the recipient + change outputs, version and locktime, plus the computed
 * fee. The signer builds the BIP-143 sighash per input, signs, and serializes the witness tx. No key here.
 */
public record BtcUnsignedTxDto(
        UUID withdrawalId,
        String network,
        int version,
        long locktime,
        long feeSat,
        List<BtcTxInputDto> inputs,
        List<BtcTxOutputDto> outputs) {
}
