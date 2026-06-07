package com.truholdem.dto;

import java.math.BigDecimal;

import com.truholdem.model.CashSeatStatus;

/** A seat at a cash table (stack visible; hole cards are not exposed here). */
public record CashSeatResponse(
        int seatNumber,
        String playerName,
        BigDecimal stack,
        CashSeatStatus status) {
}
