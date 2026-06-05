package com.truholdem.dto;

import jakarta.validation.constraints.Min;

/** Player's choice of a higher-level pyramid seat to buy (level ≥ 2, seat index ≥ 0). */
public record BuyPyramidSeatRequest(
        @Min(value = 2, message = "level must be at least 2") int level,
        @Min(value = 0, message = "seatIndex must be non-negative") int seatIndex) {
}
