package com.truholdem.dto.wallet;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;

/** Admin request listing the dedicated wallets whose (empty) USDT ATAs should be closed to reclaim rent. */
public record AtaCloseRequest(@NotEmpty List<UUID> walletIds) {
}
