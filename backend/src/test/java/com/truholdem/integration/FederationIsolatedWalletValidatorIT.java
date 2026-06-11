package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
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

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.FederationWalletImportRequest;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.model.FederationWalletStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.repository.FederationPlayerWalletRepository;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.wallet.crypto.SolKeys;
import com.truholdem.tools.OfflineDepositPoolGenerator;

/**
 * On-chain proof of the isolated-custody federated pyramid against a real {@code solana-test-validator}: a player
 * registers (assigned a dedicated wallet), pays the buy-in by an on-chain USDT transfer into that wallet's ATA,
 * and {@code reconcileDeposits} reads the balance and seats the player. Treasury + mint use fixed seeds so the
 * generator derives the same ATAs the test funds.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Isolated federated pyramid — on-chain deposit gating (solana-test-validator)")
class FederationIsolatedWalletValidatorIT {

    @Container
    static final GenericContainer<?> VALIDATOR = new GenericContainer<>("solanalabs/solana:v1.18.26")
            .withExposedPorts(8899)
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(
                    "solana-test-validator", "--rpc-port", "8899", "--ledger", "/tmp/test-ledger", "--reset"))
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(180));

    private static final byte[] TREASURY_SEED = seed(0xD1);
    private static final byte[] MINT_SEED = seed(0xD2);
    private static final String TREASURY_ADDR = SolKeys.addressFromSeed(TREASURY_SEED);
    private static final String MINT_ADDR = SolKeys.addressFromSeed(MINT_SEED);
    private static final BigDecimal BUY_IN = new BigDecimal("5");
    private static final String URL = "http://127.0.0.1:8899";

    @Autowired
    private FederatedPyramidService federatedService;
    @Autowired
    private FederationPlayerWalletRepository walletRepository;
    @Autowired
    private PyramidFederationRegistrationRepository registrationRepository;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.payments.enabled", () -> true);
        registry.add("app.payments.min-confirmations", () -> 1);
        registry.add("app.payments.sol-rpc-enabled", () -> true);
        registry.add("app.payments.sol-rpc-url",
                () -> "http://" + VALIDATOR.getHost() + ":" + VALIDATOR.getMappedPort(8899));
        registry.add("app.tournament.federated-pyramid-enabled", () -> true);
        registry.add("app.tournament.federated-isolated-wallets-enabled", () -> true);
        registry.add("app.tournament.pyramid-default-seats-per-table", () -> 2);
        registry.add("app.tournament.pyramid-default-hands-per-round", () -> 1);
    }

    @BeforeAll
    static void provisionLedger() throws Exception {
        awaitRpcReady();
        writeKeypairFile("/tmp/treasury.json", TREASURY_SEED);
        writeKeypairFile("/tmp/mint.json", MINT_SEED);
        exec("solana config set --url " + URL + " --keypair /tmp/treasury.json");
        exec("solana airdrop 100");
        exec("spl-token create-token /tmp/mint.json --decimals 6");
        exec("spl-token create-account " + MINT_ADDR);
        exec("spl-token mint " + MINT_ADDR + " 1000");
    }

    @Test
    @DisplayName("register → on-chain buy-in deposit → reconcile seats the player")
    void onChainDepositSeatsPlayer() throws Exception {
        PyramidFederation fed = federatedService.createFederation(
                "Isolated-validator " + UUID.randomUUID(), 4, 2, null, BUY_IN, CryptoAsset.USDT_SOL, false, 0, true);
        UUID federationId = fed.getId();

        byte[] seed = ("iso-validator-" + federationId).getBytes(StandardCharsets.UTF_8);
        List<FederationWalletImportRequest.Entry> entries =
                OfflineDepositPoolGenerator.generateFederationWallets(seed, federationId, 3, MINT_ADDR).stream()
                        .map(w -> new FederationWalletImportRequest.Entry(w.index(), w.ownerPubkey(), w.address()))
                        .toList();
        federatedService.importPlayerWallets(federationId, entries);

        UUID player = UUID.randomUUID();
        FederationPlayerWallet wallet = federatedService.registerIsolated(federationId, player, "P1");

        // The player pays the buy-in: an on-chain USDT transfer into their dedicated wallet's ATA (funded create).
        exec("spl-token transfer " + MINT_ADDR + " 5 " + wallet.getOwnerPubkey()
                + " --fund-recipient --allow-unfunded-recipient");

        int seated = 0;
        for (int i = 0; i < 60 && seated == 0; i++) {
            seated = federatedService.reconcileDeposits(federationId);
            if (seated == 0) {
                Thread.sleep(500);
            }
        }
        assertThat(seated).as("player seated after on-chain deposit").isEqualTo(1);

        assertThat(walletRepository.findById(wallet.getId()).orElseThrow().getStatus())
                .isEqualTo(FederationWalletStatus.FUNDED);
        var reg = registrationRepository.findByFederationIdAndPlayerId(federationId, player).orElseThrow();
        assertThat(reg.isDepositConfirmed()).isTrue();
        assertThat(reg.getShardIndex()).isGreaterThanOrEqualTo(0);
    }

    private static void awaitRpcReady() throws Exception {
        for (int i = 0; i < 60; i++) {
            var r = VALIDATOR.execInContainer("sh", "-c", "solana -u " + URL + " cluster-version");
            if (r.getExitCode() == 0) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("solana-test-validator RPC not ready");
    }

    private static String exec(String cmd) throws Exception {
        var r = VALIDATOR.execInContainer("sh", "-c", "export HOME=/root; " + cmd + " 2>&1");
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("Command failed (" + r.getExitCode() + "): " + cmd + "\n" + r.getStdout());
        }
        return r.getStdout().trim();
    }

    private static void writeKeypairFile(String path, byte[] seed) throws Exception {
        byte[] pub = SolKeys.publicKeyFromSeed(seed);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 32; i++) {
            sb.append(seed[i] & 0xff).append(',');
        }
        for (int i = 0; i < 32; i++) {
            sb.append(pub[i] & 0xff).append(i < 31 ? "," : "");
        }
        sb.append(']');
        exec("printf '%s' '" + sb + "' > " + path);
    }

    private static byte[] seed(int b) {
        byte[] s = new byte[32];
        s[0] = (byte) b;
        return s;
    }
}
