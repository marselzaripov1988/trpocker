package com.truholdem.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.truholdem.model.CryptoAsset;
import com.truholdem.model.PyramidBuyout;

/** Confirmation of a bought higher-level pyramid seat: who bought what, at which level/seat, for how much. */
public record PyramidSeatPurchaseResponse(
        UUID tournamentId,
        UUID playerId,
        int level,
        int seatIndex,
        BigDecimal price,
        CryptoAsset asset) {

    public static PyramidSeatPurchaseResponse from(PyramidBuyout buyout) {
        return new PyramidSeatPurchaseResponse(
                buyout.getTournamentId(),
                buyout.getBuyerPlayerId(),
                buyout.getLevel(),
                buyout.getSeatIndex(),
                buyout.getPriceAmount(),
                buyout.getAsset());
    }
}
