package com.truholdem.dto.wallet;

/** Outcome of a watch-only deposit notification: the ingestion status and whether a credit was applied. */
public record DepositIngestResponse(String status, boolean credited) {
}
