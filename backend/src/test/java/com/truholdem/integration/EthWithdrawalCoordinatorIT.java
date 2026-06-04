package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WalletAccount;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.repository.WalletAccountRepository;
import com.truholdem.service.wallet.WalletService;
import com.truholdem.service.wallet.crypto.EthKeys;
import com.truholdem.service.wallet.crypto.EthTransactionSigner;
import com.truholdem.service.wallet.eth.EthAbi;
import com.truholdem.service.wallet.eth.EthRpcClient;
import com.truholdem.service.wallet.eth.EthWithdrawalCoordinator;

/**
 * End-to-end ETH withdrawal against a real {@code geth --dev} node: the online coordinator assembles the
 * unsigned tx from live node state, an offline signer (test sources) EIP-155-signs it, the coordinator
 * broadcasts the raw tx and reconciles the receipt to CONFIRMED — and the recipient's on-chain balance moves.
 * The private key never reaches production code: it lives only in this test and signs out-of-band.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("ETH withdrawal coordinator (geth --dev)")
class EthWithdrawalCoordinatorIT {

    @Container
    static final GenericContainer<?> GETH = new GenericContainer<>("ethereum/client-go:v1.13.15")
            .withExposedPorts(8545)
            .withCommand("--dev", "--http", "--http.addr", "0.0.0.0", "--http.port", "8545",
                    "--http.api", "eth,web3,net", "--http.corsdomain", "*", "--dev.period", "0",
                    "--verbosity", "1")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(120));

    /** Treasury sending key — TEST ONLY. Its address is funded from the unlocked dev account at test start. */
    private static final BigInteger SENDER_KEY =
            new BigInteger("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16);
    private static final String FROM_ADDRESS = EthKeys.addressFromPrivateKey(SENDER_KEY);
    private static final String RECIPIENT = "0x000000000000000000000000000000000000dEaD";

    private static String rpcUrl() {
        return "http://" + GETH.getHost() + ":" + GETH.getMappedPort(8545);
    }

    @DynamicPropertySource
    static void ethProps(DynamicPropertyRegistry registry) {
        registry.add("app.payments.enabled", () -> true);
        registry.add("app.payments.eth-rpc-enabled", () -> true);
        registry.add("app.payments.eth-rpc-url", EthWithdrawalCoordinatorIT::rpcUrl);
        registry.add("app.payments.eth-chain-id", () -> 0); // auto-query the node
        registry.add("app.payments.eth-from-address", () -> FROM_ADDRESS);
        registry.add("app.payments.provider", () -> "offline-pool"); // approve keeps APPROVED (no in-proc broadcast)
        registry.add("app.payments.kyc-required-for-withdrawal", () -> false);
        registry.add("app.payments.withdrawal-approval-required", () -> true);
        registry.add("app.payments.min-confirmations", () -> 1);
    }

    @Autowired
    private WalletService walletService;
    @Autowired
    private EthWithdrawalCoordinator coordinator;
    @Autowired
    private EthRpcClient rpc;
    @Autowired
    private WalletAccountRepository accountRepository;

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Raw JSON-RPC call against the dev node (used only to fund our address from the unlocked dev account). */
    private static JsonNode rawRpc(String method, String paramsJson) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(rpcUrl()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(resp.body()).get("result");
    }

    private void fundSenderAddress() throws Exception {
        String dev = rawRpc("eth_accounts", "[]").get(0).asText();
        rawRpc("eth_sendTransaction",
                "[{\"from\":\"" + dev + "\",\"to\":\"" + FROM_ADDRESS + "\",\"value\":\"0xde0b6b3a7640000\"}]");
        BigInteger oneEth = BigInteger.TEN.pow(18);
        for (int i = 0; i < 50 && rpc.balance(FROM_ADDRESS).compareTo(oneEth) < 0; i++) {
            Thread.sleep(100);
        }
        assertThat(rpc.balance(FROM_ADDRESS)).isGreaterThanOrEqualTo(oneEth);
    }

    @Test
    @DisplayName("assemble → offline-sign → broadcast → reconcile to CONFIRMED, recipient balance moves")
    void ethWithdrawalEndToEnd() throws Exception {
        fundSenderAddress();
        BigInteger recipientBefore = rpc.balance(RECIPIENT);

        UUID user = UUID.randomUUID();
        WalletAccount account = new WalletAccount(user, CryptoAsset.ETH);
        account.credit(new BigDecimal("1.0"));
        accountRepository.save(account);

        BigDecimal amount = new BigDecimal("0.01");
        WithdrawalRequest req = walletService.requestWithdrawal(user, CryptoAsset.ETH, RECIPIENT, amount);
        walletService.approveWithdrawal(req.getId(), UUID.randomUUID());

        // Online: assemble the unsigned tx from the node.
        var unsigned = coordinator.buildUnsigned(req.getId());
        assertThat(unsigned.from()).isEqualToIgnoringCase(FROM_ADDRESS);

        // Offline (test sources only): EIP-155-sign with the air-gapped key.
        String rawTx = EthTransactionSigner.signLegacyTransaction(
                SENDER_KEY,
                EthAbi.hexToBigInteger(unsigned.nonce()),
                EthAbi.hexToBigInteger(unsigned.gasPrice()),
                BigInteger.valueOf(unsigned.gasLimit()),
                EthAbi.hexToBytes(unsigned.to()),
                EthAbi.hexToBigInteger(unsigned.value()),
                EthAbi.hexToBytes(unsigned.data()),
                unsigned.chainId());

        // Online: broadcast + reconcile.
        WithdrawalRequest broadcast = coordinator.broadcast(req.getId(), rawTx);
        assertThat(broadcast.getStatus()).isEqualTo(WithdrawalStatus.BROADCAST);
        assertThat(broadcast.getTxId()).startsWith("0x");

        WithdrawalRequest confirmed = null;
        for (int i = 0; i < 50; i++) {
            confirmed = coordinator.reconcile(req.getId());
            if (confirmed.getStatus() == WithdrawalStatus.CONFIRMED) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(confirmed.getStatus()).isEqualTo(WithdrawalStatus.CONFIRMED);

        BigInteger moved = rpc.balance(RECIPIENT).subtract(recipientBefore);
        assertThat(moved).isEqualTo(BigInteger.TEN.pow(16)); // 0.01 ETH in wei
    }
}
