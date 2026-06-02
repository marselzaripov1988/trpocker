package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.model.CryptoAsset;

/**
 * Default {@link CryptoPaymentProvider} for dev/tests: deterministic fake addresses and tx ids, no network
 * calls. Active when {@code app.payments.provider=mock} (the default / unset). Set the property to
 * {@code gateway} to activate {@link GatewayCryptoPaymentProvider} instead.
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "mock", matchIfMissing = true)
public class MockCryptoPaymentProvider implements CryptoPaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(MockCryptoPaymentProvider.class);

    @Override
    public String allocateDepositAddress(UUID userId, CryptoAsset asset) {
        String address = "mock-" + asset.getNetwork().toLowerCase() + "-"
                + Integer.toHexString(userId.hashCode());
        log.debug("Mock deposit address for {} {}: {}", userId, asset, address);
        return address;
    }

    @Override
    public String broadcastWithdrawal(UUID userId, CryptoAsset asset, String toAddress, BigDecimal amount) {
        String txId = "mock-tx-" + UUID.randomUUID();
        log.info("Mock broadcast withdrawal: {} {} {} → {} (tx {})", amount, asset, userId, toAddress, txId);
        return txId;
    }
}
