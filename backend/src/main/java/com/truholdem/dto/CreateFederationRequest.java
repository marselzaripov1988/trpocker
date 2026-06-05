package com.truholdem.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Admin request to create a federated pyramid: the total field is split into shards of {@code shardSize}.
 * {@code registrationDeadline} may be null (indefinite registration window). Seats/hands come from config.
 */
public record CreateFederationRequest(
        @NotBlank @Size(min = 3, max = 100) String name,
        @Positive long startingPlayers,
        @Min(2) int shardSize,
        Instant registrationDeadline) {
}
