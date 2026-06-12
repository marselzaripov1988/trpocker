package com.truholdem.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotEmpty;

/**
 * Admin import body for an isolated-custody federation's dedicated per-player wallets. The wallets are generated
 * OFFLINE (private keys never leave the air-gapped machine); only the public {@code ownerPubkey} (ed25519
 * authority), its {@code derivationIndex} (for offline re-derivation), and the {@code address} (the owner's USDT
 * ATA = deposit target) are imported.
 *
 * <p>{@code federationId} is the tournament the generated file declares it belongs to. When present it is
 * cross-checked against the import target so a chunk from a <em>different</em> tournament can't be imported by
 * mistake; it is omitted by older files (then the check is skipped). Unknown extra metadata is tolerated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FederationWalletImportRequest(String federationId, @NotEmpty List<Entry> wallets) {

    public record Entry(long derivationIndex, String ownerPubkey, String address) {
    }
}
