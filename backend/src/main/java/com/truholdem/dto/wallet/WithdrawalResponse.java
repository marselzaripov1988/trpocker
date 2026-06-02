package com.truholdem.dto.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;

public record WithdrawalResponse(
        UUID id, String asset, String network, String toAddress,
        BigDecimal amount, WithdrawalStatus status, String txId) {

    public static WithdrawalResponse from(WithdrawalRequest w) {
        return new WithdrawalResponse(w.getId(), w.getAsset().getSymbol(), w.getAsset().getNetwork(),
                w.getToAddress(), w.getAmount(), w.getStatus(), w.getTxId());
    }
}
