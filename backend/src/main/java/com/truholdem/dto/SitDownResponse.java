package com.truholdem.dto;

import java.math.BigDecimal;

import com.truholdem.model.CashSeatStatus;

/** Result of sitting down: the assigned seat and starting stack. */
public record SitDownResponse(
        int seatNumber,
        String playerName,
        BigDecimal stack,
        CashSeatStatus status) {
}
