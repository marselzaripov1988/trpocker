package com.truholdem.dto.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import com.truholdem.model.CryptoAsset;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Provider webhook body: an on-chain deposit has reached the required confirmations. */
public record DepositConfirmationRequest(
        @NotNull UUID userId,
        @NotNull CryptoAsset asset,
        @NotBlank String txId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount) {
}
