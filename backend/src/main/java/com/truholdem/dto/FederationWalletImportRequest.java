package com.truholdem.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/**
 * Admin import body for an isolated-custody federation's dedicated per-player wallets. The wallets are generated
 * OFFLINE (private keys never leave the air-gapped machine); only the public {@code ownerPubkey} (ed25519
 * authority), its {@code derivationIndex} (for offline re-derivation), and the {@code address} (the owner's USDT
 * ATA = deposit target) are imported.
 */
public record FederationWalletImportRequest(@NotEmpty List<Entry> wallets) {

    public record Entry(long derivationIndex, String ownerPubkey, String address) {
    }
}
