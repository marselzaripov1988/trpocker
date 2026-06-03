package com.truholdem.dto.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.truholdem.model.WithdrawalRequest;

/**
 * Signer-ready description of an APPROVED withdrawal handed to the offline signer (PSBT/raw-tx handoff). The
 * online server has no node, so it exports the <em>intent</em> (pay {@code amount} of {@code asset} to
 * {@code toAddress}); the air-gapped signer builds, signs and broadcasts the chain transaction with the seed
 * + its own node, then the resulting tx id is recorded back via the broadcast endpoint.
 */
public record WithdrawalSigningRequestDto(
        UUID withdrawalId,
        String asset,
        String network,
        String toAddress,
        BigDecimal amount,
        Instant createdAt) {

    public static WithdrawalSigningRequestDto from(WithdrawalRequest w) {
        return new WithdrawalSigningRequestDto(w.getId(), w.getAsset().getSymbol(), w.getAsset().getNetwork(),
                w.getToAddress(), w.getAmount(), w.getCreatedAt());
    }
}
