package com.truholdem.dto;

import java.math.BigDecimal;

import com.truholdem.model.CryptoAsset;

/** One buyable higher-level pyramid seat offered to a player: its level, seat index, price + paying asset. */
public record BuyoutTicketResponse(int level, int seatIndex, BigDecimal price, CryptoAsset asset) {
}
