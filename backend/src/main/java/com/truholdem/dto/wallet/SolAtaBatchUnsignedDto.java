package com.truholdem.dto.wallet;

import java.util.List;
import java.util.UUID;

/**
 * An unsigned batch transaction for ATA lifecycle management on an isolated-custody federation, handed to the
 * air-gapped signer. {@code operation} is {@code "create"} (idempotently create each dedicated wallet's USDT ATA,
 * paid by the operator — the only signer) or {@code "close"} (reclaim each ATA's rent to the operator, authorized
 * by each wallet owner).
 *
 * <p>The signer signs {@code messageBase64} once per entry in {@code signers}, <b>in order</b> — index 0 is the
 * {@code feePayer} (the operator), followed by each wallet owner (re-derived offline by {@code derivationIndex}).
 * For {@code create} there is only the operator signer; for {@code close} every owner signs too. The signatures
 * are then concatenated in this order to serialize the transaction for broadcast. {@code walletIds} lists the
 * covered wallets (owner order matches the owner signers) so the caller can confirm them after the batch lands.
 */
public record SolAtaBatchUnsignedDto(
        UUID federationId,
        String operation,
        String messageBase64,
        String feePayer,
        List<UUID> walletIds,
        List<Signer> signers) {

    /** One required signature. {@code derivationIndex == null} marks the operator (fee-payer); otherwise it is a
     *  dedicated wallet owner whose offline key is re-derived by its derivation index. */
    public record Signer(String pubkey, Long derivationIndex) {
    }
}
