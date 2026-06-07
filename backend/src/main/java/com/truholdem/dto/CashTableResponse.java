package com.truholdem.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.truholdem.model.CryptoAsset;

/** Lobby / config view of a cash (ring) table. */
public record CashTableResponse(
        UUID id,
        String name,
        CryptoAsset asset,
        BigDecimal smallBlind,
        BigDecimal bigBlind,
        BigDecimal minBuyIn,
        BigDecimal maxBuyIn,
        int maxSeats,
        int rakeBasisPoints,
        BigDecimal rakeCap,
        int seatedPlayers,
        boolean active) {
}
