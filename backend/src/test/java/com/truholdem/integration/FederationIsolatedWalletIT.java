package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

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
import com.truholdem.service.tournament.FederationPlayerWalletService;
import com.truholdem.tools.OfflineDepositPoolGenerator;

/**
 * Core logic of the isolated-custody federated pyramid (no validator): create an isolated USDT_SOL federation,
 * import offline-generated dedicated wallets, register a player (assigns a wallet, unseated + unconfirmed), then
 * confirm the on-chain buy-in deposit → the wallet is FUNDED and the player is seated into a shard. Idempotency
 * and the flag/asset guards are checked too. The on-chain deposit detection (`reconcileDeposits`) is proven on a
 * real `solana-test-validator` in a separate test.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.payments.min-confirmations=1",
        "app.tournament.federated-pyramid-enabled=true",
        "app.tournament.federated-isolated-wallets-enabled=true",
        "app.tournament.pyramid-default-seats-per-table=2",
        "app.tournament.pyramid-default-hands-per-round=1"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Isolated-custody federated pyramid — dedicated per-player wallets")
class FederationIsolatedWalletIT {

    private static final BigDecimal BUY_IN = new BigDecimal("5");
    // Any valid base58 mint (mainnet USDT) — the ATA derivation just needs a real mint for the test.
    private static final String MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";

    @Autowired
    private FederatedPyramidService federatedService;
    @Autowired
    private FederationPlayerWalletService walletPoolService;
    @Autowired
    private FederationPlayerWalletRepository walletRepository;
    @Autowired
    private PyramidFederationRegistrationRepository registrationRepository;

    private UUID federationId;

    @BeforeEach
    void setUp() {
        PyramidFederation fed = federatedService.createFederation(
                "Isolated " + UUID.randomUUID(), 4, 2, null, BUY_IN, CryptoAsset.USDT_SOL, false, 0, true);
        federationId = fed.getId();
        byte[] seed = ("isolated-it-" + federationId).getBytes(StandardCharsets.UTF_8);
        List<FederationWalletImportRequest.Entry> entries =
                OfflineDepositPoolGenerator.generateFederationWallets(seed, federationId, 3, MINT).stream()
                        .map(w -> new FederationWalletImportRequest.Entry(w.index(), w.ownerPubkey(), w.address()))
                        .toList();
        assertThat(walletPoolService.importBatch(federationId, CryptoAsset.USDT_SOL, entries)).isEqualTo(3);
    }

    @Test
    @DisplayName("register assigns a dedicated wallet (unseated); a confirmed deposit seats the player")
    void registerThenConfirmSeats() {
        UUID player = UUID.randomUUID();
        FederationPlayerWallet wallet = federatedService.registerIsolated(federationId, player, "P1");
        assertThat(wallet.getStatus()).isEqualTo(FederationWalletStatus.ASSIGNED);
        assertThat(wallet.getAddress()).isNotBlank();

        var reg = registrationRepository.findByFederationIdAndPlayerId(federationId, player).orElseThrow();
        assertThat(reg.isDepositConfirmed()).isFalse();
        assertThat(reg.getShardIndex()).isEqualTo(-1);           // not yet seated
        assertThat(reg.getWalletAddress()).isEqualTo(wallet.getAddress());

        // Confirm the on-chain buy-in deposit → FUNDED + seated.
        boolean seated = federatedService.confirmDeposit(federationId, wallet.getAddress(), "tx-1", BUY_IN, 1);
        assertThat(seated).isTrue();

        assertThat(walletRepository.findById(wallet.getId()).orElseThrow().getStatus())
                .isEqualTo(FederationWalletStatus.FUNDED);
        var seatedReg = registrationRepository.findByFederationIdAndPlayerId(federationId, player).orElseThrow();
        assertThat(seatedReg.isDepositConfirmed()).isTrue();
        assertThat(seatedReg.getShardIndex()).isGreaterThanOrEqualTo(0);

        // Idempotent: re-confirming the same wallet is a no-op.
        assertThat(federatedService.confirmDeposit(federationId, wallet.getAddress(), "tx-1", BUY_IN, 1)).isFalse();
    }

    @Test
    @DisplayName("re-registering returns the same dedicated wallet")
    void registerIdempotent() {
        UUID player = UUID.randomUUID();
        String first = federatedService.registerIsolated(federationId, player, "P1").getAddress();
        String again = federatedService.registerIsolated(federationId, player, "P1").getAddress();
        assertThat(again).isEqualTo(first);
    }

    @Test
    @DisplayName("an under-confirmed or under-funded deposit does not seat")
    void underConfirmedOrUnderFunded() {
        UUID player = UUID.randomUUID();
        FederationPlayerWallet wallet = federatedService.registerIsolated(federationId, player, "P1");
        assertThat(federatedService.confirmDeposit(federationId, wallet.getAddress(), "tx", BUY_IN, 0)).isFalse();
        assertThat(federatedService.confirmDeposit(federationId, wallet.getAddress(), "tx",
                new BigDecimal("4.99"), 1)).isFalse();
    }

    @Test
    @DisplayName("isolated custody requires a USDT_SOL buy-in")
    void requiresUsdtSol() {
        assertThatThrownBy(() -> federatedService.createFederation(
                "bad", 4, 2, null, BUY_IN, CryptoAsset.USDT_ERC20, false, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
