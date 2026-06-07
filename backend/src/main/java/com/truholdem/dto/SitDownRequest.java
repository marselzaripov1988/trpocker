package com.truholdem.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Request to sit down at a cash table with a real-money buy-in (asset major units). */
public record SitDownRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal buyIn) {
}
