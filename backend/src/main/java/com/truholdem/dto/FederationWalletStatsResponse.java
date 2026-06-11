package com.truholdem.dto;

import java.math.BigDecimal;

/**
 * Dashboard view of an isolated-custody federation's dedicated-wallet pool: how many wallets are imported and
 * where they sit in the lifecycle (FREE buffer / ASSIGNED-awaiting-deposit / FUNDED / REFUNDED), how many have
 * their USDT ATA pre-created, and the total buy-in collected on-chain. Lets an admin see deposit progress and
 * whether the FREE buffer + ATA provisioning are keeping ahead of registrations.
 */
public record FederationWalletStatsResponse(
        long total,
        long free,
        long assigned,
        long funded,
        long refunded,
        long ataProvisioned,
        BigDecimal fundedAmount) {
}
