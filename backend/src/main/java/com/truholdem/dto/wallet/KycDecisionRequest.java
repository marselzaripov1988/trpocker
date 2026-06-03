package com.truholdem.dto.wallet;

import com.truholdem.model.KycStatus;

import jakarta.validation.constraints.NotNull;

/** Moderator KYC decision after reviewing the verification document (VERIFIED or REJECTED). */
public record KycDecisionRequest(
        @NotNull KycStatus status,
        String note) {
}
