package com.truholdem.dto;

import java.math.BigDecimal;

import com.truholdem.model.CryptoAsset;

/** A buyable final seat in a buy-up federated pyramid: claiming it closes the empty shard at {@code shardIndex}. */
public record FinalSeatResponse(int shardIndex, BigDecimal price, CryptoAsset asset) {
}
