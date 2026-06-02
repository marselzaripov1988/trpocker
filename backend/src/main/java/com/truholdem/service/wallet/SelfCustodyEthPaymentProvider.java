package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;
import com.truholdem.service.wallet.crypto.EthKeys;

/**
 * Self-custody ETH provider — generates deposit addresses WITHOUT any external payment provider, using
 * pure-Java key derivation ({@link EthKeys}). Active when {@code app.payments.provider=eth-self-custody}.
 * Demonstrates that address generation needs no gateway: each user gets a deterministic ETH address derived
 * from a configured master key.
 *
 * <p><b>Demo-grade, deposits only.</b> Production self-custody must keep the master key in an HSM and derive
 * deposit addresses watch-only from a BIP-32 xpub (so the web tier never holds private keys), plus run a
 * node/indexer to detect deposits (→ the {@code /internal/wallet/deposit} webhook). Withdrawal signing +
 * broadcast are intentionally NOT wired here — they require an offline signer/HSM and a node RPC.
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "eth-self-custody")
public class SelfCustodyEthPaymentProvider implements CryptoPaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(SelfCustodyEthPaymentProvider.class);

    private final byte[] masterSeed;

    public SelfCustodyEthPaymentProvider(AppProperties appProperties) {
        String key = appProperties.getPayments().getSelfCustodyMasterKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "app.payments.self-custody-master-key is required for the eth-self-custody provider");
        }
        this.masterSeed = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String allocateDepositAddress(UUID userId, CryptoAsset asset) {
        if (asset != CryptoAsset.ETH) {
            throw new UnsupportedOperationException("Self-custody provider supports only ETH; got " + asset);
        }
        // Deterministic per-user key/address. Demo KDF — production: watch-only BIP-32 derivation from an xpub.
        BigInteger priv = EthKeys.derivePrivateKey(masterSeed, "eth/" + userId);
        String address = EthKeys.addressFromPrivateKey(priv);
        log.debug("Self-custody ETH deposit address for {}: {}", userId, address);
        return address;
    }

    @Override
    public String broadcastWithdrawal(UUID userId, CryptoAsset asset, String toAddress, BigDecimal amount) {
        throw new UnsupportedOperationException(
                "Self-custody ETH withdrawal is not wired: it requires an offline signer/HSM holding the "
                        + "master key and a node RPC to broadcast the signed transaction.");
    }
}
