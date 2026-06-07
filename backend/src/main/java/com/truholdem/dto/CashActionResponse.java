package com.truholdem.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Result of an action: whether it completed the hand and, if so, the rake taken + players cashed out. */
public record CashActionResponse(
        boolean handComplete,
        BigDecimal totalRake,
        List<UUID> cashedOut) {
}
