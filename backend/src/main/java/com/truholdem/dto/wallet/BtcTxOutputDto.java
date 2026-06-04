package com.truholdem.dto.wallet;

/** A transaction output: an amount (satoshis) paid to a {@code scriptPubKey} (hex). */
public record BtcTxOutputDto(long valueSat, String scriptPubKey, String label) {
}
