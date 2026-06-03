package com.truholdem.dto.wallet;

/** Result of an address-pool import: how many were newly added vs skipped as duplicates. */
public record PoolImportResponse(int imported, int skipped) {
}
