package com.truholdem.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.truholdem.model.CryptoAsset;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Admin request to create a federated pyramid: the total field is split into shards of {@code shardSize}.
 * {@code registrationDeadline} may be null (indefinite registration window). Seats/hands come from config.
 * {@code buyInAmount} + {@code buyInAsset} are optional: present → real-money (charged on registration),
 * absent/zero → play-money. {@code feeBasisPoints} is the house commission on the prize pool (0–2000 bps =
 * 0–20%); null/0 = no fee.
 */
public record CreateFederationRequest(
        @NotBlank @Size(min = 3, max = 100) String name,
        @Positive long startingPlayers,
        @Min(2) int shardSize,
        Instant registrationDeadline,
        BigDecimal buyInAmount,
        CryptoAsset buyInAsset,
        boolean buyUpEnabled,
        @Min(value = 0, message = "Fee cannot be negative")
        @Max(value = 2000, message = "Fee cannot exceed 2000 bps (20%)")
        Integer feeBasisPoints,
        /** Isolated-custody variant: each player pays the buy-in on-chain into a dedicated per-player wallet
         *  (requires a USDT_SOL buy-in + the federated-isolated-wallets-enabled flag). */
        boolean isolatedWalletsEnabled) {
}
