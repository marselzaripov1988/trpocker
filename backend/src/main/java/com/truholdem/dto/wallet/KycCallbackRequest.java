package com.truholdem.dto.wallet;

import java.util.UUID;

import com.truholdem.model.KycStatus;

import jakarta.validation.constraints.NotNull;

/** KYC provider webhook body: a verification decision for a user. */
public record KycCallbackRequest(
        @NotNull UUID userId,
        @NotNull KycStatus status,
        String provider,
        String providerRef) {
}
