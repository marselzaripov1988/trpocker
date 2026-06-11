package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.truholdem.dto.wallet.BtcSweepInputDto;
import com.truholdem.dto.wallet.BtcSweepUnsignedDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.DepositAddressPoolEntry;
import com.truholdem.model.SweepBatch;
import com.truholdem.model.SweepBatchStatus;
import com.truholdem.repository.DepositAddressPoolRepository;
import com.truholdem.repository.SweepBatchRepository;
import com.truholdem.service.wallet.btc.BtcRpcClient;
import com.truholdem.service.wallet.btc.BtcScript;
import com.truholdem.service.wallet.btc.BtcSweepCoordinator;
import com.truholdem.service.wallet.crypto.Bech32;
import com.truholdem.service.wallet.crypto.BtcKeys;
import com.truholdem.service.wallet.crypto.BtcSigner;
import com.truholdem.service.wallet.crypto.BtcTxSerializer;
import com.truholdem.service.wallet.crypto.Ripemd160;

/**
 * End-to-end BTC deposit→treasury sweep against a real {@code bitcoind -regtest}: three watch-only deposit
 * addresses are each funded with a UTXO, the coordinator plans a single consolidating tx, an offline signer
 * (test sources) signs <b>each input with its own derivation key</b> and serializes the witness tx, the
 * coordinator broadcasts + reconciles to CONFIRMED — and the treasury ends up holding one consolidated UTXO.
 * Private keys never reach production code; they live only in this test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("BTC sweep coordinator (bitcoind -regtest)")
class BtcSweepCoordinatorIT {

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

    // TEST ONLY keys. The treasury (sweep target) + three deposit-pool keys, addressed for the regtest hrp.
    private static final BigInteger TREASURY_KEY =
            new BigInteger("00c87509a1c067bbde78beb793e6fa76530b6382a4c0241e5e4a9ec0a0f44dc0d3", 16);
    private static final BigInteger[] DEPOSIT_KEYS = {
            BigInteger.valueOf(1001), BigInteger.valueOf(1002), BigInteger.valueOf(1003)
    };
    private static final String TREASURY = p2wpkhRegtest(TREASURY_KEY);

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
        registry.add("app.payments.btc-rpc-url", BtcSweepCoordinatorIT::baseUrl);
        registry.add("app.payments.btc-rpc-user", () -> RPC_USER);
        registry.add("app.payments.btc-rpc-password", () -> RPC_PASS);
        registry.add("app.payments.btc-network", () -> "regtest");
        registry.add("app.payments.btc-from-address", () -> TREASURY);
        registry.add("app.payments.btc-fee-rate-sat-per-vbyte", () -> 5);
        registry.add("app.payments.btc-min-utxo-confirmations", () -> 1);
        registry.add("app.payments.min-confirmations", () -> 1);
        registry.add("app.payments.sweep.enabled", () -> true);
        registry.add("app.payments.sweep.batch-max-inputs", () -> 10);
        registry.add("app.payments.sweep.min-amount-per-asset.BTC", () -> "0.001");
    }

    @Autowired
    private BtcSweepCoordinator coordinator;
    @Autowired
    private BtcRpcClient rpc;
    @Autowired
    private DepositAddressPoolRepository poolRepository;
    @Autowired
    private SweepBatchRepository sweepRepository;

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    @Test
    @DisplayName("plan → offline-sign each input → broadcast → reconcile; treasury holds one consolidated UTXO")
    void btcSweepEndToEnd() throws Exception {
        poolRepository.deleteAll();
        sweepRepository.deleteAll();

        // Register three watch-only deposit addresses (derivation index = array index) and fund each with 1 BTC.
        Map<Long, BigInteger> keyByIndex = new HashMap<>();
        List<String> depositAddrs = new ArrayList<>();
        for (int i = 0; i < DEPOSIT_KEYS.length; i++) {
            String addr = p2wpkhRegtest(DEPOSIT_KEYS[i]);
            keyByIndex.put((long) i, DEPOSIT_KEYS[i]);
            depositAddrs.add(addr);
            DepositAddressPoolEntry entry = new DepositAddressPoolEntry(CryptoAsset.BTC, addr, i);
            entry.assignTo(UUID.randomUUID());
            poolRepository.save(entry);
        }

        rpcCall(null, "createwallet", "\"miner\"");
        String minerAddr = rpcCall("miner", "getnewaddress", "").asText();
        rpcCall(null, "generatetoaddress", "101,\"" + minerAddr + "\""); // mature the coinbase
        for (String addr : depositAddrs) {
            rpcCall("miner", "sendtoaddress", "\"" + addr + "\",1");
        }
        rpcCall(null, "generatetoaddress", "1,\"" + minerAddr + "\""); // confirm the funding txs
        for (int i = 0; i < 50 && rpc.listUnspent(depositAddrs.get(2)).isEmpty(); i++) {
            Thread.sleep(100);
        }

        // Online: plan + assemble the consolidating tx (N deposit inputs → 1 treasury output).
        BtcSweepUnsignedDto unsigned = coordinator.planSweep(CryptoAsset.BTC);
        assertThat(unsigned.inputs()).hasSize(3);
        assertThat(unsigned.toAddress()).isEqualTo(TREASURY);
        assertThat(unsigned.outValueSat()).isEqualTo(300_000_000L - unsigned.feeSat());

        // Offline (test sources only): sign each input with the key for ITS derivation index, serialize.
        String rawTx = signAndSerialize(unsigned, keyByIndex);

        SweepBatch broadcast = coordinator.broadcast(unsigned.sweepBatchId(), rawTx);
        assertThat(broadcast.getStatus()).isEqualTo(SweepBatchStatus.BROADCAST);

        rpcCall(null, "generatetoaddress", "1,\"" + minerAddr + "\""); // mine the sweep tx

        SweepBatch confirmed = null;
        for (int i = 0; i < 50; i++) {
            confirmed = coordinator.reconcile(unsigned.sweepBatchId());
            if (confirmed.getStatus() == SweepBatchStatus.CONFIRMED) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(confirmed.getStatus()).isEqualTo(SweepBatchStatus.CONFIRMED);

        // The treasury now holds exactly one consolidated UTXO worth Σ inputs − fee.
        List<BtcRpcClient.Utxo> treasuryUtxos = rpc.listUnspent(TREASURY);
        assertThat(treasuryUtxos).hasSize(1);
        assertThat(treasuryUtxos.get(0).valueSat()).isEqualTo(unsigned.outValueSat());
    }

    /** Sign each sweep input with the key for its derivation index and serialize the witness tx. */
    private String signAndSerialize(BtcSweepUnsignedDto u, Map<Long, BigInteger> keyByIndex) {
        List<BtcSigner.TxIn> ins = new ArrayList<>();
        for (BtcSweepInputDto in : u.inputs()) {
            ins.add(new BtcSigner.TxIn(BtcScript.txidToInternalBytes(in.txid()), in.vout(), 0xffffffffL));
        }
        List<BtcSigner.TxOut> outs = List.of(
                new BtcSigner.TxOut(u.outValueSat(), BtcScript.p2wpkhScriptPubKey(u.toAddress(), u.network())));
        List<BtcTxSerializer.Witness> witnesses = new ArrayList<>();
        for (int i = 0; i < u.inputs().size(); i++) {
            BtcSweepInputDto in = u.inputs().get(i);
            BigInteger key = keyByIndex.get(in.derivationIndex());
            byte[] sig = BtcSigner.signP2wpkhInput(key, u.version(), ins, outs, i, in.valueSat(), u.locktime());
            witnesses.add(new BtcTxSerializer.Witness(sig, BtcKeys.compressedPublicKey(key)));
        }
        return BtcTxSerializer.serialize(u.version(), ins, outs, witnesses, u.locktime());
    }
}
