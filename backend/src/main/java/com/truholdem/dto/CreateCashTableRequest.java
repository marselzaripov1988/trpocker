package com.truholdem.dto;

import java.math.BigDecimal;

import com.truholdem.model.CryptoAsset;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Admin request to create a cash (ring) table. Amounts are in the asset's major units. */
public record CreateCashTableRequest(
        @NotBlank @Size(max = 64) String name,
        @NotNull CryptoAsset asset,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal smallBlind,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal bigBlind,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal minBuyIn,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal maxBuyIn,
        @Min(2) @Max(10) int maxSeats,
        @Min(0) @Max(10000) int rakeBasisPoints,
        @NotNull @PositiveOrZero BigDecimal rakeCap) {
}
