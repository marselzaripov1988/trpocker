package com.truholdem.dto.wallet;

import java.math.BigDecimal;

import com.truholdem.model.CryptoAsset;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Watch-only deposit notification keyed by destination address (sent by a node/indexer that scans the pooled
 * addresses). The server resolves the owning user from the address itself.
 */
public record DepositByAddressRequest(
        @NotNull CryptoAsset asset,
        @NotBlank String address,
        @NotBlank String txId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        @PositiveOrZero int confirmations) {
}
