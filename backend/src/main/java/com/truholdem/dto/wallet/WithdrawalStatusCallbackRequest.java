package com.truholdem.dto.wallet;

import java.util.UUID;

import com.truholdem.model.WithdrawalStatus;

import jakarta.validation.constraints.NotNull;

/**
 * Provider webhook body: the terminal outcome of a broadcast withdrawal. Only {@code CONFIRMED} and
 * {@code FAILED} are valid here (validated in the controller).
 */
public record WithdrawalStatusCallbackRequest(
        @NotNull UUID withdrawalId,
        @NotNull WithdrawalStatus status) {
}
