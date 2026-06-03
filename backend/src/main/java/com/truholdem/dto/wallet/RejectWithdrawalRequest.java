package com.truholdem.dto.wallet;

/** Moderator's reason for rejecting a withdrawal (optional, stored for audit). */
public record RejectWithdrawalRequest(String reason) {
}
