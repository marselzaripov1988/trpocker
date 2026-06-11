package com.truholdem.dto.wallet;

import jakarta.validation.constraints.NotBlank;

/** Admin request carrying the offline-signed, base64-serialized Solana transaction to broadcast. */
public record SolBroadcastRequest(@NotBlank String signedTx) {
}
