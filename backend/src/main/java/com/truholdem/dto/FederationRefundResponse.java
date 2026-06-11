package com.truholdem.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.FederationRefund;
import com.truholdem.model.FederationRefundStatus;

/** Read view of an isolated-custody refund. */
public record FederationRefundResponse(
        UUID id,
        UUID federationId,
        UUID walletId,
        UUID playerId,
        CryptoAsset asset,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal netAmount,
        String toAddress,
        FederationRefundStatus status,
        String txId) {

    public static FederationRefundResponse from(FederationRefund r) {
        return new FederationRefundResponse(r.getId(), r.getFederationId(), r.getWalletId(), r.getPlayerId(),
                r.getAsset(), r.getGrossAmount(), r.getFeeAmount(), r.getNetAmount(), r.getToAddress(),
                r.getStatus(), r.getTxId());
    }
}
