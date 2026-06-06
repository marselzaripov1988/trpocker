package com.truholdem.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.truholdem.model.CryptoAsset;

/** Confirmation of a bought final seat: the buyer becomes the finalist of the (now closed) shard. */
public record FinalSeatPurchaseResponse(
        UUID federationId,
        UUID playerId,
        int shardIndex,
        BigDecimal price,
        CryptoAsset asset) {
}
