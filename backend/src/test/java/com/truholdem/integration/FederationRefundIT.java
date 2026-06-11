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
import com.truholdem.model.FederationRefund;
import com.truholdem.model.FederationRefundStatus;
import com.truholdem.model.FederationWalletStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.repository.FederationPlayerWalletRepository;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.tournament.FederationPlayerWalletService;
import com.truholdem.service.tournament.FederationRefundService;
import com.truholdem.service.wallet.crypto.SolKeys;
import com.truholdem.tools.OfflineDepositPoolGenerator;

/**
 * Admin-approved refund state machine (no validator): request → approve (sets destination) → broadcast →
 * confirm marks the wallet REFUNDED. The player is made whole — refunded the full gross (net = gross, fee = 0;
 * the operator absorbs the network cost). Approval gate + address validation are checked. The on-chain transfer
 * is proven on a real validator in {@code FederationRefundValidatorIT}.
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
@DisplayName("Isolated-custody refund — admin-approved state machine")
class FederationRefundIT {

    private static final BigDecimal BUY_IN = new BigDecimal("5");
    private static final String MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB";
    private static final String PLAYER_ADDR = SolKeys.addressFromSeed(seed32(0x5A));

    private static byte[] seed32(int b) {
        byte[] s = new byte[32];
        s[0] = (byte) b;
        return s;
    }

    @Autowired
    private FederatedPyramidService federatedService;
    @Autowired
    private FederationPlayerWalletService walletPoolService;
    @Autowired
    private FederationRefundService refundService;
    @Autowired
    private FederationPlayerWalletRepository walletRepository;

    private UUID federationId;
    private UUID player;
    private FederationPlayerWallet wallet;

    @BeforeEach
    void setUp() {
        PyramidFederation fed = federatedService.createFederation(
                "Refund " + UUID.randomUUID(), 4, 2, null, BUY_IN, CryptoAsset.USDT_SOL, false, 0, true);
        federationId = fed.getId();
        byte[] seed = ("refund-it-" + federationId).getBytes(StandardCharsets.UTF_8);
        List<FederationWalletImportRequest.Entry> entries =
                OfflineDepositPoolGenerator.generateFederationWallets(seed, federationId, 3, MINT).stream()
                        .map(w -> new FederationWalletImportRequest.Entry(w.index(), w.ownerPubkey(), w.address()))
                        .toList();
        walletPoolService.importBatch(federationId, CryptoAsset.USDT_SOL, entries);
        player = UUID.randomUUID();
        wallet = federatedService.registerIsolated(federationId, player, "P1");
        federatedService.confirmDeposit(federationId, wallet.getAddress(), "dep", BUY_IN, 1); // → FUNDED
    }

    @Test
    @DisplayName("refunds the full gross (no fee); approve → broadcast → confirm marks the wallet REFUNDED")
    void fullRefundFlow() {
        FederationRefund refund = refundService.requestRefund(federationId, player);
        assertThat(refund.getStatus()).isEqualTo(FederationRefundStatus.PENDING_APPROVAL);
        assertThat(refund.getGrossAmount()).isEqualByComparingTo("5");
        assertThat(refund.getFeeAmount()).isEqualByComparingTo("0");      // operator absorbs the network fee
        assertThat(refund.getNetAmount()).isEqualByComparingTo("5");      // player made whole

        refundService.approveRefund(refund.getId(), UUID.randomUUID(), PLAYER_ADDR);
        assertThat(refundService.get(refund.getId()).getStatus()).isEqualTo(FederationRefundStatus.APPROVED);
        assertThat(refundService.get(refund.getId()).getToAddress()).isEqualTo(PLAYER_ADDR);

        refundService.recordBroadcast(refund.getId(), "sig-1");
        refundService.confirm(refund.getId());
        assertThat(refundService.get(refund.getId()).getStatus()).isEqualTo(FederationRefundStatus.CONFIRMED);
        assertThat(walletRepository.findById(wallet.getId()).orElseThrow().getStatus())
                .isEqualTo(FederationWalletStatus.REFUNDED);
    }

    @Test
    @DisplayName("nothing is signable until approved; a pending refund can be rejected")
    void approvalGate() {
        FederationRefund refund = refundService.requestRefund(federationId, player);
        assertThatThrownBy(() -> refundService.forSigning(refund.getId()))
                .isInstanceOf(IllegalStateException.class); // not APPROVED yet
        refundService.rejectRefund(refund.getId(), UUID.randomUUID(), "duplicate");
        assertThat(refundService.get(refund.getId()).getStatus()).isEqualTo(FederationRefundStatus.REJECTED);
    }

    @Test
    @DisplayName("request is idempotent per wallet; approve rejects a malformed address")
    void idempotentAndAddressValidation() {
        UUID first = refundService.requestRefund(federationId, player).getId();
        UUID again = refundService.requestRefund(federationId, player).getId();
        assertThat(again).isEqualTo(first);

        assertThatThrownBy(() -> refundService.approveRefund(first, UUID.randomUUID(), "not-base58-0OIl"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
