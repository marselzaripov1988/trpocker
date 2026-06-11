package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
import com.truholdem.dto.wallet.SolRefundUnsignedDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.model.FederationRefund;
import com.truholdem.model.FederationRefundStatus;
import com.truholdem.model.FederationWalletStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.repository.FederationPlayerWalletRepository;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.tournament.FederationRefundService;
import com.truholdem.service.wallet.crypto.SolKeys;
import com.truholdem.service.wallet.sol.SolRefundCoordinator;
import com.truholdem.service.wallet.sol.SolTransactionTestKit;
import com.truholdem.tools.OfflineDepositPoolGenerator;

/**
 * End-to-end admin-approved refund against a real {@code solana-test-validator}: a funded dedicated wallet is
 * refunded to the player's address by an offline-signed SPL transfer with TWO signers — the operator (fee-payer)
 * and the dedicated wallet's owner (transfer authority) — and the player's on-chain USDT balance moves. Keys are
 * test-only.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Isolated-custody refund (solana-test-validator)")
class FederationRefundValidatorIT {

    @Container
    static final GenericContainer<?> VALIDATOR = new GenericContainer<>("solanalabs/solana:v1.18.26")
            .withExposedPorts(8899)
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(
                    "solana-test-validator", "--rpc-port", "8899", "--ledger", "/tmp/test-ledger", "--reset"))
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(180));

    private static final byte[] TREASURY_SEED = seed(0xE1);
    private static final byte[] MINT_SEED = seed(0xE2);
    private static final String TREASURY_ADDR = SolKeys.addressFromSeed(TREASURY_SEED);
    private static final String MINT_ADDR = SolKeys.addressFromSeed(MINT_SEED);
    private static final String PLAYER_DEST = SolKeys.addressFromSeed(seed(0xE3)); // player's external refund address
    private static final BigDecimal BUY_IN = new BigDecimal("5");
    private static final String URL = "http://127.0.0.1:8899";

    @Autowired
    private FederatedPyramidService federatedService;
    @Autowired
    private FederationRefundService refundService;
    @Autowired
    private SolRefundCoordinator refundCoordinator;
    @Autowired
    private com.truholdem.service.wallet.sol.SolanaRpcClient rpc;
    @Autowired
    private FederationPlayerWalletRepository walletRepository;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.payments.enabled", () -> true);
        registry.add("app.payments.min-confirmations", () -> 1);
        registry.add("app.payments.sol-rpc-enabled", () -> true);
        registry.add("app.payments.sol-rpc-url",
                () -> "http://" + VALIDATOR.getHost() + ":" + VALIDATOR.getMappedPort(8899));
        registry.add("app.payments.sol-from-address", () -> TREASURY_ADDR);
        registry.add("app.payments.sol-usdt-mint", () -> MINT_ADDR);
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
    @DisplayName("approve → offline two-signer refund → reconcile; player's USDT balance moves")
    void refundEndToEnd() throws Exception {
        PyramidFederation fed = federatedService.createFederation(
                "Refund-validator " + UUID.randomUUID(), 4, 2, null, BUY_IN, CryptoAsset.USDT_SOL, false, 0, true);
        UUID federationId = fed.getId();
        byte[] genSeed = ("refund-validator-" + federationId).getBytes(StandardCharsets.UTF_8);

        List<FederationWalletImportRequest.Entry> entries =
                OfflineDepositPoolGenerator.generateFederationWallets(genSeed, federationId, 3, MINT_ADDR).stream()
                        .map(w -> new FederationWalletImportRequest.Entry(w.index(), w.ownerPubkey(), w.address()))
                        .toList();
        federatedService.importPlayerWallets(federationId, entries);

        UUID player = UUID.randomUUID();
        FederationPlayerWallet wallet = federatedService.registerIsolated(federationId, player, "P1");

        // Player funds their dedicated wallet on-chain, then we detect it.
        exec("spl-token transfer " + MINT_ADDR + " 5 " + wallet.getOwnerPubkey()
                + " --fund-recipient --allow-unfunded-recipient");
        for (int i = 0; i < 60 && federatedService.reconcileDeposits(federationId) == 0; i++) {
            Thread.sleep(500);
        }

        // Admin-approved refund to the player's external address.
        FederationRefund refund = refundService.requestRefund(federationId, player);
        refundService.approveRefund(refund.getId(), UUID.randomUUID(), PLAYER_DEST);

        SolRefundUnsignedDto unsigned = refundCoordinator.buildUnsigned(refund.getId());
        assertThat(unsigned.feePayer()).isEqualTo(TREASURY_ADDR);

        // Offline: sign with BOTH the operator (fee-payer) and the dedicated wallet owner (authority), in order.
        byte[] message = Base64.getDecoder().decode(unsigned.messageBase64());
        byte[] operatorSig = SolKeys.sign(TREASURY_SEED, message);
        byte[] walletSeed = OfflineDepositPoolGenerator.federationWalletSeed(
                genSeed, federationId, unsigned.authorityDerivationIndex());
        byte[] walletSig = SolKeys.sign(walletSeed, message);
        String signedTx = SolTransactionTestKit.serialize(List.of(operatorSig, walletSig), message);

        refundCoordinator.broadcast(refund.getId(), signedTx);
        FederationRefund confirmed = null;
        for (int i = 0; i < 60; i++) {
            confirmed = refundCoordinator.reconcile(refund.getId());
            if (confirmed.getStatus() == FederationRefundStatus.CONFIRMED) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(confirmed.getStatus()).isEqualTo(FederationRefundStatus.CONFIRMED);

        // The player received the net amount (fee 0 → full 5 USDT; dest ATA created in the same tx) + wallet REFUNDED.
        assertThat(unsigned.createsDestAta()).isTrue();
        assertThat(rpc.getTokenAccountBalance(unsigned.destAta())).isEqualTo(BigInteger.valueOf(5_000_000L));
        assertThat(walletRepository.findById(wallet.getId()).orElseThrow().getStatus())
                .isEqualTo(FederationWalletStatus.REFUNDED);
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
