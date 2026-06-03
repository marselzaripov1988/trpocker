package com.truholdem.dto.wallet;

import java.math.BigDecimal;

import com.truholdem.model.CryptoAsset;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Real-money tournament buy-in: which crypto asset and how much to debit from the wallet. */
public record TournamentBuyInRequest(
        @NotNull CryptoAsset asset,
        @NotNull @Positive BigDecimal amount) {
}
