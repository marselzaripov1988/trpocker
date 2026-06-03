package com.truholdem.dto.wallet;

import jakarta.validation.constraints.NotBlank;

/** The on-chain tx id produced by the offline signer after broadcasting an approved withdrawal. */
public record BroadcastWithdrawalRequest(@NotBlank String txId) {
}
