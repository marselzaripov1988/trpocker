package com.truholdem.dto.wallet;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

/** Body of the admin deposit-address-pool import: a batch of public addresses generated offline. */
public record PoolImportRequest(
        @NotEmpty @Valid List<PoolEntryDto> addresses) {
}
