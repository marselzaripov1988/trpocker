package com.truholdem.dto.wallet;

/**
 * On-chain status of a broadcast withdrawal's transaction: whether it is mined, whether it succeeded, how many
 * confirmations it has, and whether that meets the configured threshold.
 */
public record EthConfirmationDto(
        String txId,
        boolean mined,
        boolean success,
        long confirmations,
        boolean confirmed) {
}
