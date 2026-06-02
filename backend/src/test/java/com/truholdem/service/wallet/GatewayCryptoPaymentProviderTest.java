package com.truholdem.service.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.truholdem.config.AppProperties;
import com.truholdem.model.CryptoAsset;

@DisplayName("GatewayCryptoPaymentProvider — HTTP gateway adapter (sandbox/testnet)")
class GatewayCryptoPaymentProviderTest {

    private static final String BASE = "https://api-sandbox.example.io/v1";

    private AppProperties appProperties;
    private MockRestServiceServer server;
    private GatewayCryptoPaymentProvider provider;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getPayments().setProvider("gateway");
        appProperties.getPayments().setNetwork("testnet");
        appProperties.getPayments().setGatewayBaseUrl(BASE);
        appProperties.getPayments().setGatewayApiKey("test-key");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new GatewayCryptoPaymentProvider(builder.build(), appProperties);
    }

    @Test
    @DisplayName("allocateDepositAddress posts to the gateway with the api key and parses the address")
    void allocatesDepositAddress() {
        server.expect(requestTo(BASE + "/deposit-addresses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andRespond(withSuccess("{\"address\":\"TTESTNETaddr123\"}", MediaType.APPLICATION_JSON));

        String address = provider.allocateDepositAddress(UUID.randomUUID(), CryptoAsset.USDT_TRC20);

        assertThat(address).isEqualTo("TTESTNETaddr123");
        server.verify();
    }

    @Test
    @DisplayName("broadcastWithdrawal posts to the gateway payouts endpoint and parses the tx id")
    void broadcastsWithdrawal() {
        server.expect(requestTo(BASE + "/payouts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andRespond(withSuccess("{\"txId\":\"testnet-tx-abc\"}", MediaType.APPLICATION_JSON));

        String txId = provider.broadcastWithdrawal(
                UUID.randomUUID(), CryptoAsset.USDT_TRC20, "TToAddr", new BigDecimal("12.5"));

        assertThat(txId).isEqualTo("testnet-tx-abc");
        server.verify();
    }
}
