package com.truholdem.dto.wallet;

/**
 * The unsigned Solana withdrawal handed to the air-gapped signer: {@code messageBase64} is the exact compiled
 * legacy-message bytes to ed25519-sign (with the treasury key), {@code feePayer} is the treasury owner that
 * signs. The signer prepends its 64-byte signature and base64-serializes the full transaction for
 * {@code broadcast(...)}. {@code createsRecipientAta} flags that the tx also lazily creates the recipient's ATA.
 */
public record SolUnsignedTxDto(
        String messageBase64,
        String feePayer,
        String recipientAta,
        long amount,
        boolean createsRecipientAta) {
}
