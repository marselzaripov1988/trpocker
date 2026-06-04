package com.truholdem.service.wallet;

import com.truholdem.model.CryptoAsset;

/** Wallet domain exceptions (mapped to HTTP status by the controller). */
public final class WalletExceptions {

    private WalletExceptions() {
    }

    /** The offline-generated deposit-address pool has no free address left for the asset (needs a refill). */
    public static class DepositAddressPoolExhaustedException extends RuntimeException {
        public DepositAddressPoolExhaustedException(CryptoAsset asset) {
            super("No free deposit addresses left in the pool for " + asset + "; import a new batch");
        }
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

    /** The withdrawal exceeds a configured per-transaction or rolling-24h limit. */
    public static class WithdrawalLimitExceededException extends RuntimeException {
        public WithdrawalLimitExceededException(String message) {
            super(message);
        }
    }

    /** A withdrawal cannot be approved yet — it is still within its mandatory cooling period. */
    public static class WithdrawalCoolingPeriodException extends IllegalStateException {
        public WithdrawalCoolingPeriodException(String message) {
            super(message);
        }
    }

    /** A KYC upload was rejected (e.g. flagged by the AV scan). */
    public static class KycMediaRejectedException extends RuntimeException {
        public KycMediaRejectedException(String message) {
            super(message);
        }
    }
}
