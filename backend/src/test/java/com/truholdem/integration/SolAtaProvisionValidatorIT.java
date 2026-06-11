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
import com.truholdem.dto.wallet.SolAtaBatchUnsignedDto;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.FederationPlayerWallet;
import com.truholdem.model.FederationWalletStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.repository.FederationPlayerWalletRepository;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.wallet.crypto.SolKeys;
import com.truholdem.service.wallet.sol.SolAtaProvisioner;
import com.truholdem.service.wallet.sol.SolanaRpcClient;
import com.truholdem.service.wallet.sol.SolTransactionTestKit;
import com.truholdem.tools.OfflineDepositPoolGenerator;

/**
 * End-to-end ATA lifecycle against a real {@code solana-test-validator}:
 * <ol>
 *   <li><b>create</b> — an offline operator-signed batch pre-creates a dedicated wallet's USDT ATA, after which a
 *       <em>bare</em> SPL transfer (no {@code --fund-recipient}, the way an exchange sends) lands the buy-in and
 *       the player seats. This proves the ATA must (and does) pre-exist.</li>
 *   <li><b>close</b> — an offline batch signed by the operator (fee-payer) <em>and</em> the wallet owner closes the
 *       empty ATA, returning its rent lamports to the operator.</li>
 * </ol>
 * Keys are test-only.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Isolated-custody ATA provisioning (solana-test-validator)")
class SolAtaProvisionValidatorIT {

    @Container
    static final GenericContainer<?> VALIDATOR = new GenericContainer<>("solanalabs/solana:v1.18.26")
            .withExposedPorts(8899)
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(
                    "solana-test-validator", "--rpc-port", "8899", "--ledger", "/tmp/test-ledger", "--reset"))
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(180));

    private static final byte[] TREASURY_SEED = seed(0xA1);
    private static final byte[] MINT_SEED = seed(0xA2);
    private static final String TREASURY_ADDR = SolKeys.addressFromSeed(TREASURY_SEED);
    private static final String MINT_ADDR = SolKeys.addressFromSeed(MINT_SEED);
    private static final BigDecimal BUY_IN = new BigDecimal("5");
    private static final String URL = "http://127.0.0.1:8899";

    @Autowired
    private FederatedPyramidService federatedService;
    @Autowired
    private SolAtaProvisioner ataProvisioner;
    @Autowired
    private SolanaRpcClient rpc;
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
        // Read at confirmed so spl-token sees a just-confirmed (not yet finalized) ATA on a bare transfer.
        exec("solana config set --url " + URL + " --keypair /tmp/treasury.json --commitment confirmed");
        exec("solana airdrop 100");
        exec("spl-token create-token /tmp/mint.json --decimals 6");
        exec("spl-token create-account " + MINT_ADDR);
        exec("spl-token mint " + MINT_ADDR + " 1000");
    }

    @Test
    @DisplayName("pre-created ATA lets a bare exchange-style transfer land + seat the player")
    void provisionThenBareDepositLands() throws Exception {
        PyramidFederation fed = federatedService.createFederation(
                "Ata-create " + UUID.randomUUID(), 4, 2, null, BUY_IN, CryptoAsset.USDT_SOL, false, 0, true);
        UUID federationId = fed.getId();
        byte[] genSeed = ("ata-validator-create-" + federationId).getBytes(StandardCharsets.UTF_8);
        importWallets(federationId, genSeed, 3);

        UUID player = UUID.randomUUID();
        FederationPlayerWallet wallet = federatedService.registerIsolated(federationId, player, "P1");

        // Operator-signed batch pre-creates the ATAs (single signer = fee-payer).
        SolAtaBatchUnsignedDto batch = ataProvisioner.buildCreateBatch(federationId, 10);
        assertThat(batch.operation()).isEqualTo("create");
        assertThat(batch.signers()).singleElement().satisfies(s -> {
            assertThat(s.pubkey()).isEqualTo(TREASURY_ADDR);
            assertThat(s.derivationIndex()).isNull();
        });
        String signature = signAndBroadcast(batch, genSeed, federationId);
        pollUntil(() -> ataProvisioner.confirmCreated(federationId, batch.walletIds(), signature) > 0);
        assertThat(walletRepository.findById(wallet.getId()).orElseThrow().isAtaProvisioned()).isTrue();

        // spl-token reads the recipient at finalized — wait for the create tx to finalize before the deposit.
        pollUntil(() -> "finalized".equals(rpc.getSignatureStatus(signature).confirmationStatus()));

        // Exchange-style deposit: send straight to the deposit address (the ATA itself), with NO --fund-recipient.
        // This only succeeds because the ATA pre-exists — exactly the rail an exchange withdrawal uses.
        exec("spl-token transfer " + MINT_ADDR + " 5 " + wallet.getAddress());
        pollUntil(() -> federatedService.reconcileDeposits(federationId) > 0);

        assertThat(rpc.getTokenAccountBalance(wallet.getAddress())).isEqualTo(BigInteger.valueOf(5_000_000L));
        assertThat(walletRepository.findById(wallet.getId()).orElseThrow().getStatus())
                .isEqualTo(FederationWalletStatus.FUNDED);
    }

    @Test
    @DisplayName("closing an empty ATA returns its rent to the operator")
    void closeReclaimsRent() throws Exception {
        PyramidFederation fed = federatedService.createFederation(
                "Ata-close " + UUID.randomUUID(), 4, 2, null, BUY_IN, CryptoAsset.USDT_SOL, false, 0, true);
        UUID federationId = fed.getId();
        byte[] genSeed = ("ata-validator-close-" + federationId).getBytes(StandardCharsets.UTF_8);
        importWallets(federationId, genSeed, 1);

        // Create the ATA first.
        SolAtaBatchUnsignedDto create = ataProvisioner.buildCreateBatch(federationId, 10);
        String createSig = signAndBroadcast(create, genSeed, federationId);
        pollUntil(() -> ataProvisioner.confirmCreated(federationId, create.walletIds(), createSig) > 0);

        FederationPlayerWallet wallet = walletRepository.findById(create.walletIds().get(0)).orElseThrow();
        assertThat(rpc.getTokenAccountsByOwner(wallet.getOwnerPubkey(), MINT_ADDR)).isNotEmpty(); // ATA exists
        BigInteger operatorBefore = rpc.getBalance(TREASURY_ADDR);

        // Close batch: operator (fee-payer) + the wallet owner both sign.
        SolAtaBatchUnsignedDto close = ataProvisioner.buildCloseBatch(federationId, List.of(wallet.getId()));
        assertThat(close.operation()).isEqualTo("close");
        assertThat(close.signers()).hasSize(2);
        String closeSig = signAndBroadcast(close, genSeed, federationId);
        pollUntil(() -> ataProvisioner.confirmClosed(federationId, close.walletIds(), closeSig) > 0);

        // The ATA is gone and the operator got the rent back (net of the small tx fee).
        assertThat(rpc.getTokenAccountsByOwner(wallet.getOwnerPubkey(), MINT_ADDR)).isEmpty();
        assertThat(rpc.getBalance(TREASURY_ADDR)).isGreaterThan(operatorBefore);
        assertThat(walletRepository.findById(wallet.getId()).orElseThrow().isAtaProvisioned()).isFalse();
    }

    // --- helpers ---

    private void importWallets(UUID federationId, byte[] genSeed, int count) {
        List<FederationWalletImportRequest.Entry> entries =
                OfflineDepositPoolGenerator.generateFederationWallets(genSeed, federationId, count, MINT_ADDR).stream()
                        .map(w -> new FederationWalletImportRequest.Entry(w.index(), w.ownerPubkey(), w.address()))
                        .toList();
        federatedService.importPlayerWallets(federationId, entries);
    }

    /** Offline-sign the batch's message with each required signer (operator key, or the owner key re-derived by
     *  its derivation index), in signer order, then broadcast and return the signature. */
    private String signAndBroadcast(SolAtaBatchUnsignedDto batch, byte[] genSeed, UUID federationId) {
        byte[] message = Base64.getDecoder().decode(batch.messageBase64());
        List<byte[]> sigs = new java.util.ArrayList<>();
        for (SolAtaBatchUnsignedDto.Signer signer : batch.signers()) {
            byte[] key = signer.derivationIndex() == null
                    ? TREASURY_SEED
                    : OfflineDepositPoolGenerator.federationWalletSeed(genSeed, federationId, signer.derivationIndex());
            sigs.add(SolKeys.sign(key, message));
        }
        return ataProvisioner.broadcast(SolTransactionTestKit.serialize(sigs, message));
    }

    private static void pollUntil(java.util.function.BooleanSupplier cond) throws Exception {
        for (int i = 0; i < 60; i++) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("condition not met within timeout");
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
