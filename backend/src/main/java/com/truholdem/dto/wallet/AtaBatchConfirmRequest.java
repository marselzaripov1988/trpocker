package com.truholdem.dto.wallet;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/** Admin request to confirm a broadcast ATA batch: the transaction {@code signature} plus the wallets it covered
 *  (returned by the unsigned-batch call), which are marked provisioned/closed once the signature confirms. */
public record AtaBatchConfirmRequest(@NotBlank String signature, @NotEmpty List<UUID> walletIds) {
}
