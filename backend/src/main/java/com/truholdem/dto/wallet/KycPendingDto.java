package com.truholdem.dto.wallet;

import java.time.Instant;
import java.util.UUID;

import com.truholdem.model.KycStatus;

/** A pending KYC submission awaiting moderator review (user + latest uploaded document metadata). */
public record KycPendingDto(
        UUID userId,
        KycStatus status,
        Instant uploadedAt,
        String originalFilename,
        String contentType,
        long sizeBytes) {
}
