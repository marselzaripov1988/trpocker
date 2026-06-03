package com.truholdem.dto.wallet;

import com.truholdem.model.CryptoAsset;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** One public deposit address from an offline-generated batch (no private material). */
public record PoolEntryDto(
        @NotNull CryptoAsset asset,
        @PositiveOrZero long derivationIndex,
        @NotBlank String address) {
}
