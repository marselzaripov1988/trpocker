package com.truholdem.dto.wallet;

/**
 * A selected UTXO to spend, for the offline signer. {@code txid} is the display (big-endian) hash; the signer
 * reverses it for the transaction. {@code valueSat} (the prevout amount) is required for the BIP-143 sighash.
 */
public record BtcTxInputDto(String txid, long vout, long valueSat, String scriptPubKey) {
}
