package com.truholdem.dto.wallet;

/** On-chain status of a broadcast BTC withdrawal: confirmations and whether the threshold is met. */
public record BtcConfirmationDto(String txId, long confirmations, boolean confirmed) {
}
