package com.truholdem.service.wallet;

/** Wallet domain exceptions (mapped to HTTP status by the controller). */
public final class WalletExceptions {

    private WalletExceptions() {
    }

    /** The wallet subsystem is disabled (feature flag off). */
    public static class PaymentsDisabledException extends RuntimeException {
        public PaymentsDisabledException() {
            super("Payments are disabled");
        }
    }

    /** A withdrawal was attempted before the user's KYC is VERIFIED. */
    public static class KycRequiredException extends RuntimeException {
        public KycRequiredException() {
            super("KYC verification is required before withdrawal");
        }
    }

    /** The wallet balance is too low for the requested debit. */
    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException() {
            super("Insufficient funds");
        }
    }
}
