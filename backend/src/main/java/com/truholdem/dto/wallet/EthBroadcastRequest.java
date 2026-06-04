package com.truholdem.dto.wallet;

import jakarta.validation.constraints.NotBlank;

/** The offline-signed raw Ethereum transaction (0x-hex) to broadcast for an approved withdrawal. */
public record EthBroadcastRequest(@NotBlank String signedRawTx) {
}
