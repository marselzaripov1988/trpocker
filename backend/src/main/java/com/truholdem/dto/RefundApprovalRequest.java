package com.truholdem.dto;

import jakarta.validation.constraints.NotBlank;

/** Admin approval of an isolated-custody refund: the player's Solana address to return the buy-in to. */
public record RefundApprovalRequest(@NotBlank String toAddress) {
}
