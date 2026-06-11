package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Base64;

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
import com.truholdem.dto.wallet.SolUnsignedTxDto;
import com.truholdem.service.wallet.crypto.SolAta;
import com.truholdem.service.wallet.crypto.SolKeys;
import com.truholdem.service.wallet.sol.SolWithdrawalCoordinator;

/**
 * End-to-end USDT-on-Solana (SPL) withdrawal against a real {@code solana-test-validator}: the coordinator
 * assembles the unsigned SPL transfer (creating the recipient ATA in the same tx), an offline ed25519 signer
 * (test sources) signs the compiled message and serializes the transaction, the coordinator broadcasts and the
 * signature reaches {@code confirmed} — and the recipient's on-chain USDT balance moves. The treasury private
 * key never reaches production code; it lives only in this test. Treasury + mint use fixed seeds so their
 * addresses are known before the validator starts (for config binding).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("USDT-Solana withdrawal coordinator (solana-test-validator)")
class SolWithdrawalCoordinatorIT {

    @Container
    static final GenericContainer<?> VALIDATOR = new GenericContainer<>("solanalabs/solana:v1.18.26")
            .withExposedPorts(8899)
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(
                    "solana-test-validator", "--rpc-port", "8899", "--ledger", "/tmp/test-ledger", "--reset"))
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofSeconds(180));

    // TEST ONLY fixed seeds → deterministic addresses (known before the container starts).
    private static final byte[] TREASURY_SEED = seed(0xA1);
    private static final byte[] MINT_SEED = seed(0xB2);
    private static final byte[] RECIPIENT_SEED = seed(0xC3);
    private static final String TREASURY_ADDR = SolKeys.addressFromSeed(TREASURY_SEED);
    private static final String MINT_ADDR = SolKeys.addressFromSeed(MINT_SEED);
    private static final String RECIPIENT_ADDR = SolKeys.addressFromSeed(RECIPIENT_SEED);

    private static final long AMOUNT = 250_000_000L; // 250 USDT (6 decimals)

    @Autowired
    private SolWithdrawalCoordinator coordinator;
    @Autowired
    private com.truholdem.service.wallet.sol.SolanaRpcClient rpc;
    @Autowired
    private com.truholdem.service.wallet.WalletService walletService;
    @Autowired
    private com.truholdem.repository.WalletAccountRepository accountRepository;

    @DynamicPropertySource
    static void solProps(DynamicPropertyRegistry registry) {
        registry.add("app.payments.enabled", () -> true);
        registry.add("app.payments.sol-rpc-enabled", () -> true);
        registry.add("app.payments.sol-rpc-url",
                () -> "http://" + VALIDATOR.getHost() + ":" + VALIDATOR.getMappedPort(8899));
        registry.add("app.payments.sol-from-address", () -> TREASURY_ADDR);
        registry.add("app.payments.sol-usdt-mint", () -> MINT_ADDR);
        registry.add("app.payments.provider", () -> "offline-pool"); // approve keeps APPROVED (offline signing)
        registry.add("app.payments.kyc-required-for-withdrawal", () -> false);
        registry.add("app.payments.withdrawal-approval-required", () -> true);
        registry.add("app.payments.min-confirmations", () -> 1);
    }

    private static final String URL = "http://127.0.0.1:8899";

    @BeforeAll
    static void provisionLedger() throws Exception {
        awaitRpcReady();
        // Use the treasury keypair (our fixed seed) as the CLI default signer, so it is the fee payer + mint
        // authority + token-account owner for setup — and the same key the offline Java signer uses below.
        writeKeypairFile("/tmp/treasury.json", TREASURY_SEED);
        writeKeypairFile("/tmp/mint.json", MINT_SEED);
        exec("solana config set --url " + URL + " --keypair /tmp/treasury.json");
        exec("solana airdrop 100");                                // fund the treasury (fees + ATA rents)

        // Create the USDT mint (6 decimals) at our fixed mint address, the treasury's ATA, and mint 1000 USDT.
        exec("spl-token create-token /tmp/mint.json --decimals 6");
        exec("spl-token create-account " + MINT_ADDR);             // treasury's associated USDT account
        exec("spl-token mint " + MINT_ADDR + " 1000");             // → treasury's ATA
    }

    @Test
    @DisplayName("withdrawal: request → approve → assemble → offline-sign → broadcast → reconcile; balance moves")
    void usdtWithdrawalEndToEnd() throws Exception {
        // A USDT-Solana withdrawal goes through the normal wallet flow: credit, request (debit + APPROVED).
        java.util.UUID user = java.util.UUID.randomUUID();
        com.truholdem.model.WalletAccount account =
                new com.truholdem.model.WalletAccount(user, com.truholdem.model.CryptoAsset.USDT_SOL);
        account.credit(new java.math.BigDecimal("250"));
        accountRepository.save(account);
        var req = walletService.requestWithdrawal(user, com.truholdem.model.CryptoAsset.USDT_SOL,
                RECIPIENT_ADDR, new java.math.BigDecimal("250"));
        walletService.approveWithdrawal(req.getId(), java.util.UUID.randomUUID());

        // Online: assemble the unsigned SPL transfer (recipient ATA does not exist yet → created in-tx).
        SolUnsignedTxDto unsigned = coordinator.buildUnsigned(req.getId());
        assertThat(unsigned.createsRecipientAta()).isTrue();
        assertThat(unsigned.feePayer()).isEqualTo(TREASURY_ADDR);

        // Offline (test sources only): ed25519-sign the compiled message + serialize the transaction.
        byte[] message = Base64.getDecoder().decode(unsigned.messageBase64());
        byte[] signature = SolKeys.sign(TREASURY_SEED, message);
        String signedTx = com.truholdem.service.wallet.sol.SolTransactionTestKit.serialize(signature, message);

        // Online: broadcast + reconcile through the withdrawal state machine.
        var broadcast = coordinator.broadcast(req.getId(), signedTx);
        assertThat(broadcast.getStatus()).isEqualTo(com.truholdem.model.WithdrawalStatus.BROADCAST);

        com.truholdem.model.WithdrawalRequest confirmed = null;
        for (int i = 0; i < 60; i++) {
            confirmed = coordinator.reconcile(req.getId());
            if (confirmed.getStatus() == com.truholdem.model.WithdrawalStatus.CONFIRMED) {
                break;
            }
            Thread.sleep(500);
        }
        assertThat(confirmed.getStatus()).isEqualTo(com.truholdem.model.WithdrawalStatus.CONFIRMED);

        // The recipient's USDT ATA now holds the transferred amount (250 USDT = 250_000_000 base units).
        BigInteger balance = rpc.getTokenAccountBalance(unsigned.recipientAta());
        assertThat(balance).isEqualTo(BigInteger.valueOf(AMOUNT));
    }

    // --- helpers ---

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
        // docker exec does not set HOME → pin it so `solana config`/`spl-token` find ~/.config/solana/cli.
        var r = VALIDATOR.execInContainer("sh", "-c", "export HOME=/root; " + cmd + " 2>&1");
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("Command failed (" + r.getExitCode() + "): " + cmd
                    + "\n" + r.getStdout());
        }
        return r.getStdout().trim();
    }

    /** Write a Solana CLI keypair file: a JSON array of 64 bytes = seed(32) || pubkey(32). */
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
