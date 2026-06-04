package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
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
import com.truholdem.config.AppProperties;
import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.wallet.EthUnsignedTxDto;
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
 * End-to-end ERC-20 (USDT_ERC20) withdrawal against {@code geth --dev}: deploy a minimal ERC-20 (minted to the
 * treasury), then have the coordinator assemble the {@code transfer(...)} tx from live node state, sign it
 * offline, broadcast, reconcile to CONFIRMED — and assert the recipient's token balance moved via
 * {@code balanceOf}. Proves the ERC-20 calldata + eth_call decode on a real EVM, not just by vector.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("ERC-20 withdrawal coordinator (geth --dev, real token)")
class EthErc20WithdrawalCoordinatorIT {

    @Container
    static final GenericContainer<?> GETH = new GenericContainer<>("ethereum/client-go:v1.13.15")
            .withExposedPorts(8545)
            .withCommand("--dev", "--http", "--http.addr", "0.0.0.0", "--http.port", "8545",
                    "--http.api", "eth,web3,net", "--http.corsdomain", "*", "--dev.period", "0",
                    "--verbosity", "1")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(120));

    private static final BigInteger SENDER_KEY =
            new BigInteger("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16);
    private static final String FROM_ADDRESS = EthKeys.addressFromPrivateKey(SENDER_KEY);
    private static final String RECIPIENT = EthKeys.addressFromPrivateKey(BigInteger.valueOf(987654321L));

    private static String rpcUrl() {
        return "http://" + GETH.getHost() + ":" + GETH.getMappedPort(8545);
    }

    @DynamicPropertySource
    static void ethProps(DynamicPropertyRegistry registry) {
        registry.add("app.payments.enabled", () -> true);
        registry.add("app.payments.eth-rpc-enabled", () -> true);
        registry.add("app.payments.eth-rpc-url", EthErc20WithdrawalCoordinatorIT::rpcUrl);
        registry.add("app.payments.eth-chain-id", () -> 0);
        registry.add("app.payments.eth-from-address", () -> FROM_ADDRESS);
        registry.add("app.payments.erc20-gas-limit", () -> 120000);
        registry.add("app.payments.provider", () -> "offline-pool");
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
    @Autowired
    private AppProperties appProperties;

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode rawRpc(String method, String paramsJson) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + paramsJson + "}";
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(rpcUrl()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(resp.body()).get("result");
    }

    private void fund(String address, String valueHex) throws Exception {
        String dev = rawRpc("eth_accounts", "[]").get(0).asText();
        rawRpc("eth_sendTransaction",
                "[{\"from\":\"" + dev + "\",\"to\":\"" + address + "\",\"value\":\"" + valueHex + "\"}]");
    }

    /** Deploy the minimal ERC-20 from the treasury (so the supply is minted to it); returns the contract addr. */
    private String deployToken() throws Exception {
        fund(FROM_ADDRESS, "0xde0b6b3a7640000"); // 1 ETH for gas
        BigInteger oneEth = BigInteger.TEN.pow(18);
        for (int i = 0; i < 50 && rpc.balance(FROM_ADDRESS).compareTo(oneEth) < 0; i++) {
            Thread.sleep(100);
        }
        String raw = EthTransactionSigner.signLegacyTransaction(SENDER_KEY,
                rpc.pendingNonce(FROM_ADDRESS), rpc.gasPrice(), BigInteger.valueOf(400_000),
                new byte[0], BigInteger.ZERO, MinimalErc20.creationCode(), rpc.chainId());
        String deployHash = rpc.sendRawTransaction(raw);

        for (int i = 0; i < 50; i++) {
            JsonNode receipt = rawRpc("eth_getTransactionReceipt", "[\"" + deployHash + "\"]");
            if (receipt != null && !receipt.isNull() && receipt.hasNonNull("contractAddress")) {
                return receipt.get("contractAddress").asText();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("token deploy not mined");
    }

    private BigInteger tokenBalanceOf(String contract, String address) {
        String hex = rpc.call(contract, EthAbi.toHex(EthAbi.balanceOfData(EthAbi.address20(address))));
        return EthAbi.hexToBigInteger(hex);
    }

    @Test
    @DisplayName("deploy token → assemble transfer → offline-sign → broadcast → CONFIRMED, token balance moves")
    void erc20WithdrawalEndToEnd() throws Exception {
        String token = deployToken();
        BigInteger decimals = BigInteger.TEN.pow(6); // USDT has 6 decimals
        assertThat(tokenBalanceOf(token, FROM_ADDRESS)).as("supply minted to treasury").isGreaterThan(decimals);
        appProperties.getPayments().getErc20Contracts().put(CryptoAsset.USDT_ERC20.name(), token);

        UUID user = UUID.randomUUID();
        WalletAccount account = new WalletAccount(user, CryptoAsset.USDT_ERC20);
        account.credit(new BigDecimal("5.0"));
        accountRepository.save(account);

        WithdrawalRequest req = walletService.requestWithdrawal(
                user, CryptoAsset.USDT_ERC20, RECIPIENT, new BigDecimal("1.0"));
        walletService.approveWithdrawal(req.getId(), UUID.randomUUID());

        EthUnsignedTxDto unsigned = coordinator.buildUnsigned(req.getId());
        assertThat(unsigned.to()).isEqualToIgnoringCase(token);
        assertThat(EthAbi.hexToBigInteger(unsigned.value())).isZero();

        String rawTx = EthTransactionSigner.signLegacyTransaction(SENDER_KEY,
                EthAbi.hexToBigInteger(unsigned.nonce()), EthAbi.hexToBigInteger(unsigned.gasPrice()),
                BigInteger.valueOf(unsigned.gasLimit()), EthAbi.hexToBytes(unsigned.to()),
                EthAbi.hexToBigInteger(unsigned.value()), EthAbi.hexToBytes(unsigned.data()), unsigned.chainId());

        coordinator.broadcast(req.getId(), rawTx);
        WithdrawalRequest confirmed = null;
        for (int i = 0; i < 50; i++) {
            confirmed = coordinator.reconcile(req.getId());
            if (confirmed.getStatus() == WithdrawalStatus.CONFIRMED) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(confirmed.getStatus()).isEqualTo(WithdrawalStatus.CONFIRMED);
        assertThat(tokenBalanceOf(token, RECIPIENT)).as("recipient got 1 USDT (1e6 units)").isEqualTo(decimals);
    }

    /**
     * Minimal ERC-20 EVM bytecode (no Solidity toolchain in this environment): constructor mints a large
     * supply to {@code msg.sender}; the runtime implements only {@code balanceOf(address)} (0x70a08231) and
     * {@code transfer(address,uint256)} (0xa9059cbb), each over a {@code mapping(address=>uint) at slot 0}.
     * Built programmatically so the jump offsets are computed, not hand-typed.
     */
    private static final class MinimalErc20 {
        static byte[] creationCode() {
            byte[] balanceOf = bytes(0x5b, 0x60, 0x04, 0x35, 0x60, 0x00, 0x52, 0x60, 0x00, 0x60, 0x20, 0x52,
                    0x60, 0x40, 0x60, 0x00, 0x20, 0x54, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00, 0xf3);
            byte[] transfer = bytes(0x5b, 0x33, 0x60, 0x00, 0x52, 0x60, 0x00, 0x60, 0x20, 0x52, 0x60, 0x40,
                    0x60, 0x00, 0x20, 0x80, 0x54, 0x60, 0x24, 0x35, 0x90, 0x03, 0x90, 0x55, 0x60, 0x04, 0x35,
                    0x60, 0x00, 0x52, 0x60, 0x00, 0x60, 0x20, 0x52, 0x60, 0x40, 0x60, 0x00, 0x20, 0x80, 0x54,
                    0x60, 0x24, 0x35, 0x01, 0x90, 0x55, 0x60, 0x01, 0x60, 0x00, 0x52, 0x60, 0x20, 0x60, 0x00,
                    0xf3);
            int balOff = 31;                       // dispatcher length
            int trOff = balOff + balanceOf.length;  // = 57
            byte[] dispatcher = bytes(0x60, 0x00, 0x35, 0x60, 0xe0, 0x1c, 0x80,
                    0x63, 0x70, 0xa0, 0x82, 0x31, 0x14, 0x60, balOff, 0x57,
                    0x80, 0x63, 0xa9, 0x05, 0x9c, 0xbb, 0x14, 0x60, trOff, 0x57,
                    0x60, 0x00, 0x60, 0x00, 0xfd);
            byte[] runtime = concat(dispatcher, balanceOf, transfer);

            int rtLen = runtime.length;
            int rtOff = 37; // fixed init length below
            byte[] init = bytes(0x33, 0x60, 0x00, 0x52, 0x60, 0x00, 0x60, 0x20, 0x52, 0x60, 0x40, 0x60, 0x00,
                    0x20, 0x67, 0x0d, 0xe0, 0xb6, 0xb3, 0xa7, 0x64, 0x00, 0x00, 0x90, 0x55,
                    0x60, rtLen, 0x60, rtOff, 0x60, 0x00, 0x39, 0x60, rtLen, 0x60, 0x00, 0xf3);
            if (init.length != rtOff) {
                throw new IllegalStateException("init length drift: " + init.length);
            }
            return concat(init, runtime);
        }

        private static byte[] bytes(int... ops) {
            byte[] out = new byte[ops.length];
            for (int i = 0; i < ops.length; i++) {
                out[i] = (byte) ops[i];
            }
            return out;
        }

        private static byte[] concat(byte[]... parts) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            for (byte[] p : parts) {
                o.writeBytes(p);
            }
            return o.toByteArray();
        }
    }
}
