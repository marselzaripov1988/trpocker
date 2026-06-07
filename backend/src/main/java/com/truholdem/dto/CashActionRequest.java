package com.truholdem.dto;

import java.math.BigDecimal;

import com.truholdem.model.PlayerAction;

import jakarta.validation.constraints.NotNull;

/** A player action at a cash table. {@code amount} (money) is required only for BET / RAISE. */
public record CashActionRequest(
        @NotNull PlayerAction action,
        BigDecimal amount) {
}
