package com.truholdem.dto.wallet;

import jakarta.validation.constraints.NotBlank;

/** The offline-signed raw BTC transaction (hex) to broadcast for an approved withdrawal. */
public record BtcBroadcastRequest(@NotBlank String signedRawTx) {
}
