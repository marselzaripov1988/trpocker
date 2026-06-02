package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import com.truholdem.model.CryptoAsset;

/**
 * Outbound integration with a crypto payment backend (a gateway like NOWPayments/CoinsPaid, or a
 * self-custody signer). Deposit DETECTION is inbound (the provider calls our webhook), so it is not part of
 * this interface — only the two outbound operations are: allocate a deposit address and broadcast a payout.
 *
 * <p>Implementations are swapped by configuration; {@link MockCryptoPaymentProvider} backs dev/tests.
 */
public interface CryptoPaymentProvider {

    /** Returns (creating if needed) a deposit address for this user + asset/network. */
    String allocateDepositAddress(UUID userId, CryptoAsset asset);

    /** Broadcasts a payout on-chain and returns the transaction id. */
    String broadcastWithdrawal(UUID userId, CryptoAsset asset, String toAddress, BigDecimal amount);
}
