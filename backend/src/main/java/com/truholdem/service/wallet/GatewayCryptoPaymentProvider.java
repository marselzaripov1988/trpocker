package com.truholdem.service.wallet;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;

/**
 * Real {@link CryptoPaymentProvider} backed by an HTTP crypto-payment gateway (NOWPayments / CoinsPaid-style
 * REST). Active when {@code app.payments.provider=gateway}; otherwise {@link MockCryptoPaymentProvider} is used.
 *
 * <p><b>Network mode:</b> point {@code app.payments.gateway-base-url} at the provider's <em>sandbox/testnet</em>
 * endpoint and set {@code app.payments.network=testnet} to run the full flow on a value-less test network
 * (deposit addresses from a faucet, payouts broadcast on testnet) — the rest of the wallet (ledger,
 * idempotency, KYC gate, withdrawal lifecycle) is identical to mainnet. Switch to the prod base URL +
 * {@code network=mainnet} for production.
 *
 * <p><b>This is an integration skeleton.</b> The request/response shapes below are a generic gateway contract;
 * align the endpoint paths, field names and auth with your chosen gateway's API (and verify against its
 * sandbox). Deposit DETECTION stays inbound — the gateway calls {@code /internal/wallet/deposit} (see
 * {@code WalletWebhookController}); this class only does the two outbound calls.
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "gateway")
public class GatewayCryptoPaymentProvider implements CryptoPaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(GatewayCryptoPaymentProvider.class);

    private final RestClient restClient;
    private final AppProperties appProperties;

    public GatewayCryptoPaymentProvider(RestClient paymentsRestClient, AppProperties appProperties) {
        this.restClient = paymentsRestClient;
        this.appProperties = appProperties;
    }

    @Override
    public String allocateDepositAddress(UUID userId, CryptoAsset asset) {
        AppProperties.Payments cfg = appProperties.getPayments();
        Map<String, Object> body = Map.of(
                "userId", userId.toString(),
                "currency", asset.getSymbol(),
                "network", asset.getNetwork(),
                "mode", cfg.getNetwork());
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = restClient.post()
                .uri(cfg.getGatewayBaseUrl() + "/deposit-addresses")
                .header("x-api-key", cfg.getGatewayApiKey())
                .body(body)
                .retrieve()
                .body(Map.class);
        String address = resp == null ? null : (String) resp.get("address");
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("Gateway returned no deposit address for " + asset);
        }
        log.debug("Gateway deposit address for {} {} ({}): {}", userId, asset, cfg.getNetwork(), address);
        return address;
    }

    @Override
    public String broadcastWithdrawal(UUID userId, CryptoAsset asset, String toAddress, BigDecimal amount) {
        AppProperties.Payments cfg = appProperties.getPayments();
        Map<String, Object> body = Map.of(
                "userId", userId.toString(),
                "currency", asset.getSymbol(),
                "network", asset.getNetwork(),
                "mode", cfg.getNetwork(),
                "address", toAddress,
                "amount", amount.toPlainString());
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = restClient.post()
                .uri(cfg.getGatewayBaseUrl() + "/payouts")
                .header("x-api-key", cfg.getGatewayApiKey())
                .body(body)
                .retrieve()
                .body(Map.class);
        String txId = resp == null ? null : (String) resp.get("txId");
        if (txId == null || txId.isBlank()) {
            throw new IllegalStateException("Gateway returned no tx id for withdrawal of " + asset);
        }
        log.info("Gateway broadcast withdrawal {} {} {} → {} ({} tx {})",
                amount, asset, userId, toAddress, cfg.getNetwork(), txId);
        return txId;
    }
}
