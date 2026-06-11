package com.truholdem.dto.wallet;

import java.util.UUID;

/**
 * The unsigned refund transaction handed to the air-gapped signer. The refund moves USDT from a dedicated
 * player wallet to the player's address, so it has TWO signers: the {@code feePayer} (the operator, pays the SOL
 * network fee) and the {@code authority} (the dedicated wallet owner, authorizes the transfer — re-derived
 * offline by {@code authorityDerivationIndex}). The signer signs {@code messageBase64} with both keys (fee-payer
 * first) and base64-serializes the transaction for {@code broadcast}.
 */
public record SolRefundUnsignedDto(
        UUID refundId,
        String messageBase64,
        String feePayer,
        String authority,
        long authorityDerivationIndex,
        String destAta,
        long amount,
        boolean createsDestAta) {
}
