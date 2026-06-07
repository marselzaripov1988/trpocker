package com.truholdem.dto;

import java.math.BigDecimal;

/** Result of leaving: whether cashed out immediately (vs deferred to the hand end) and the amount credited. */
public record CashLeaveResponse(
        boolean cashedOutNow,
        BigDecimal amount) {
}
