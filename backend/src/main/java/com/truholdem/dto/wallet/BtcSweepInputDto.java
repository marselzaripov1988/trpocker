package com.truholdem.dto.wallet;

/**
 * One UTXO to consolidate in a sweep. Unlike a withdrawal input (all from one treasury key), a sweep's inputs
 * come from many watch-only deposit addresses — so this carries the {@code derivationIndex} (+ {@code address})
 * the offline signer needs to re-derive the <em>per-input</em> signing key. {@code valueSat} is required for
 * BIP-143 sighash.
 */
public record BtcSweepInputDto(
        String txid,
        long vout,
        long valueSat,
        String scriptPubKey,
        long derivationIndex,
        String address) {
}
