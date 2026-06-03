package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.model.CryptoAsset;

/**
 * Deposit provider backed by an offline-generated watch-only address pool. Active when
 * {@code app.payments.provider=offline-pool}. Deposit addresses were created offline (private keys + seed
 * never on this server) and imported via the admin API; this provider just hands out the next free one.
 *
 * <p>Withdrawal is intentionally NOT wired: spending pooled funds requires an offline signer (e.g. PSBT
 * workflow) that holds the seed, since the online server only ever has the public addresses.
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "offline-pool")
public class OfflinePoolCryptoPaymentProvider implements CryptoPaymentProvider {

    private final DepositAddressPoolService pool;

    public OfflinePoolCryptoPaymentProvider(DepositAddressPoolService pool) {
        this.pool = pool;
    }

    @Override
    public String allocateDepositAddress(UUID userId, CryptoAsset asset) {
        return pool.allocate(userId, asset);
    }

    @Override
    public String broadcastWithdrawal(UUID userId, CryptoAsset asset, String toAddress, BigDecimal amount) {
        throw new UnsupportedOperationException(
                "Offline-pool withdrawal is not wired: spend pooled funds with an offline signer (PSBT) that "
                        + "holds the seed; the online server only has watch-only public addresses.");
    }
}
