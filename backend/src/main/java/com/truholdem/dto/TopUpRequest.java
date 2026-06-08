package com.truholdem.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Request to add chips to an active cash-table seat between hands (capped at the table's max buy-in). */
public record TopUpRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
}
