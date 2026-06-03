package com.truholdem.dto.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;

/** Admin/moderation view of a withdrawal (includes the user + review audit fields). */
public record AdminWithdrawalDto(
        UUID id,
        UUID userId,
        String asset,
        String network,
        String toAddress,
        BigDecimal amount,
        WithdrawalStatus status,
        String txId,
        UUID reviewedBy,
        String rejectionReason,
        Instant createdAt) {

    public static AdminWithdrawalDto from(WithdrawalRequest w) {
        return new AdminWithdrawalDto(w.getId(), w.getUserId(), w.getAsset().getSymbol(),
                w.getAsset().getNetwork(), w.getToAddress(), w.getAmount(), w.getStatus(), w.getTxId(),
                w.getReviewedBy(), w.getRejectionReason(), w.getCreatedAt());
    }
}
