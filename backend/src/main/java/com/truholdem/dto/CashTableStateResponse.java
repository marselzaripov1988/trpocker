package com.truholdem.dto;

import java.util.List;

/** Full state of a cash table for a seated/watching player: config, seats, and the current hand. */
public record CashTableStateResponse(
        CashTableResponse table,
        List<CashSeatResponse> seats,
        CashHandResponse hand) {
}
