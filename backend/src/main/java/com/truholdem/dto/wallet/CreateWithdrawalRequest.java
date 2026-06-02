package com.truholdem.dto.wallet;

import java.math.BigDecimal;

import com.truholdem.model.CryptoAsset;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateWithdrawalRequest(
        @NotNull CryptoAsset asset,
        @NotBlank String toAddress,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount) {
}
