package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
import com.truholdem.dto.wallet.BtcUnsignedTxDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.WalletAccount;
import com.truholdem.model.WithdrawalRequest;
import com.truholdem.model.WithdrawalStatus;
import com.truholdem.repository.WalletAccountRepository;
import com.truholdem.service.wallet.WalletService;
import com.truholdem.service.wallet.btc.BtcRpcClient;
import com.truholdem.service.wallet.btc.BtcScript;
import com.truholdem.service.wallet.btc.BtcWithdrawalCoordinator;
import com.truholdem.service.wallet.crypto.Bech32;
import com.truholdem.service.wallet.crypto.BtcKeys;
import com.truholdem.service.wallet.crypto.BtcSigner;
import com.truholdem.service.wallet.crypto.BtcTxSerializer;
import com.truholdem.service.wallet.crypto.Ripemd160;

/**
 * End-to-end BTC (P2WPKH) withdrawal against a real {@code bitcoind -regtest}: the online coordinator selects
 * a UTXO + fee from the node and assembles the unsigned tx, an offline signer (test sources) BIP-143-signs it
 * and serializes the witness tx, the coordinator broadcasts and reconciles to CONFIRMED — and the recipient's
 * on-chain balance moves. The private key never reaches production code; it lives only in this test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("BTC withdrawal coordinator (bitcoind -regtest)")
class BtcWithdrawalCoordinatorIT {

    private static final String RPC_USER = "user";
    private static final String RPC_PASS = "pass";

    @Container
    static final GenericContainer<?> BITCOIND = new GenericContainer<>("ruimarinho/bitcoin-core:24")
            .withExposedPorts(18443)
            .withCommand("-printtoconsole", "-regtest=1", "-server=1",
                    "-rpcbind=0.0.0.0", "-rpcallowip=0.0.0.0/0", "-rpcport=18443",
                    "-rpcuser=" + RPC_USER, "-rpcpassword=" + RPC_PASS,
                    "-fallbackfee=0.0002", "-txindex=1")
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(120));

    // Treasury + recipient keys — TEST ONLY. Addresses are derived for the regtest hrp ("bcrt").
    private static final BigInteger SENDER_KEY =
            new BigInteger("00c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3", 16);
    private static final BigInteger RECIPIENT_KEY =
            new BigInteger("00f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d3", 16);
    private static final String FROM_ADDRESS = p2wpkhRegtest(SENDER_KEY);
    private static final String RECIPIENT = p2wpkhRegtest(RECIPIENT_KEY);

    private static String p2wpkhRegtest(BigInteger key) {
        return Bech32.encodeSegwit("bcrt", 0, hash160(BtcKeys.compressedPublicKey(key)));
    }

    private static byte[] hash160(byte[] data) {
        try {
            return Ripemd160.digest(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String baseUrl() {
        return "http://" + BITCOIND.getHost() + ":" + BITCOIND.getMappedPort(18443);
    }

    @DynamicPropertySource
    static void btcProps(DynamicPropertyRegistry registry) {
        registry.add("app.payments.enabled", () -> true);
        registry.add("app.payments.btc-rpc-enabled", () -> true);
        registry.add("app.payments.btc-rpc-url", BtcWithdrawalCoordinatorIT::baseUrl);
        registry.add("app.payments.btc-rpc-user", () -> RPC_USER);
        registry.add("app.payments.btc-rpc-password", () -> RPC_PASS);
        registry.add("app.payments.btc-network", () -> "regtest");
        registry.add("app.payments.btc-from-address", () -> FROM_ADDRESS);
        registry.add("app.payments.btc-fee-rate-sat-per-vbyte", () -> 5);
        registry.add("app.payments.btc-min-utxo-confirmations", () -> 1);
        registry.add("app.payments.provider", () -> "offline-pool"); // approve keeps APPROVED
        registry.add("app.payments.kyc-required-for-withdrawal", () -> false);
        registry.add("app.payments.withdrawal-approval-required", () -> true);
        registry.add("app.payments.min-confirmations", () -> 1);
    }

    @Autowired
    private WalletService walletService;
    @Autowired
    private BtcWithdrawalCoordinator coordinator;
    @Autowired
    private BtcRpcClient rpc;
    @Autowired
    private WalletAccountRepository accountRepository;

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Raw Bitcoin Core JSON-RPC (against the node, or a loaded wallet when {@code walletPath} is given). */
    private static JsonNode rpcCall(String walletPath, String method, String paramsJson) throws Exception {
        String url = baseUrl() + (walletPath == null ? "" : "/wallet/" + walletPath);
        String body = "{\"jsonrpc\":\"1.0\",\"id\":\"it\",\"method\":\"" + method + "\",\"params\":["
                + paramsJson + "]}";
        String auth = Base64.getEncoder().encodeToString(
                (RPC_USER + ":" + RPC_PASS).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Authorization", "Basic " + auth)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(resp.body()).get("result");
    }

    /** Fund the treasury address with a confirmed, non-coinbase UTXO via the node's own wallet. */
    private String fundTreasury() throws Exception {
        rpcCall(null, "createwallet", "\"miner\"");
        String minerAddr = rpcCall("miner", "getnewaddress", "").asText();
        rpcCall(null, "generatetoaddress", "101,\"" + minerAddr + "\""); // mature the coinbase
        rpcCall("miner", "sendtoaddress", "\"" + FROM_ADDRESS + "\",10");
        rpcCall(null, "generatetoaddress", "1,\"" + minerAddr + "\""); // confirm the funding tx
        for (int i = 0; i < 50 && rpc.listUnspent(FROM_ADDRESS).isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(rpc.listUnspent(FROM_ADDRESS)).as("treasury funded").isNotEmpty();
        return minerAddr;
    }

    @Test
    @DisplayName("select UTXO → offline-sign → broadcast → reconcile to CONFIRMED, recipient balance moves")
    void btcWithdrawalEndToEnd() throws Exception {
        String minerAddr = fundTreasury();

        UUID user = UUID.randomUUID();
        WalletAccount account = new WalletAccount(user, CryptoAsset.BTC);
        account.credit(new BigDecimal("5.0"));
        accountRepository.save(account);

        BigDecimal amount = new BigDecimal("1.0");
        WithdrawalRequest req = walletService.requestWithdrawal(user, CryptoAsset.BTC, RECIPIENT, amount);
        walletService.approveWithdrawal(req.getId(), UUID.randomUUID());

        // Online: assemble the unsigned tx (UTXO selection + fee/change) from the node.
        BtcUnsignedTxDto unsigned = coordinator.buildUnsigned(req.getId());
        assertThat(unsigned.inputs()).isNotEmpty();

        // Offline (test sources only): BIP-143-sign each input and serialize the witness tx.
        String rawTx = signAndSerialize(unsigned);

        // Online: broadcast + confirm.
        WithdrawalRequest broadcast = coordinator.broadcast(req.getId(), rawTx);
        assertThat(broadcast.getStatus()).isEqualTo(WithdrawalStatus.BROADCAST);

        rpcCall(null, "generatetoaddress", "1,\"" + minerAddr + "\""); // mine the withdrawal tx

        WithdrawalRequest confirmed = null;
        for (int i = 0; i < 50; i++) {
            confirmed = coordinator.reconcile(req.getId());
            if (confirmed.getStatus() == WithdrawalStatus.CONFIRMED) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(confirmed.getStatus()).isEqualTo(WithdrawalStatus.CONFIRMED);

        long recipientSat = rpc.listUnspent(RECIPIENT).stream().mapToLong(BtcRpcClient.Utxo::valueSat).sum();
        assertThat(recipientSat).as("recipient received exactly 1 BTC").isEqualTo(100_000_000L);
    }

    /** Build BtcSigner inputs/outputs from the unsigned DTO, sign each input, and serialize the witness tx. */
    private String signAndSerialize(BtcUnsignedTxDto unsigned) {
        List<BtcSigner.TxIn> ins = new ArrayList<>();
        for (var in : unsigned.inputs()) {
            ins.add(new BtcSigner.TxIn(BtcScript.txidToInternalBytes(in.txid()), in.vout(), 0xffffffffL));
        }
        List<BtcSigner.TxOut> outs = new ArrayList<>();
        for (var out : unsigned.outputs()) {
            outs.add(new BtcSigner.TxOut(out.valueSat(), BtcScript.hexToBytes(out.scriptPubKey())));
        }
        byte[] pubkey = BtcKeys.compressedPublicKey(SENDER_KEY);
        List<BtcTxSerializer.Witness> witnesses = new ArrayList<>();
        for (int i = 0; i < unsigned.inputs().size(); i++) {
            byte[] sig = BtcSigner.signP2wpkhInput(SENDER_KEY, unsigned.version(), ins, outs, i,
                    unsigned.inputs().get(i).valueSat(), unsigned.locktime());
            witnesses.add(new BtcTxSerializer.Witness(sig, pubkey));
        }
        return BtcTxSerializer.serialize(unsigned.version(), ins, outs, witnesses, unsigned.locktime());
    }
}
