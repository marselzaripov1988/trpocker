package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.wallet.WalletService;

/**
 * Buy-up federated pyramid slice 3: an admin distributes the guaranteed prize pool (the expected buy-ins,
 * {@code shardCount × shardSize × buyIn}) with an admin-chosen shard-winner share; the rest goes to the champion.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.tournament.pyramid-default-seats-per-table=2",
        "app.tournament.pyramid-default-hands-per-round=1"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Buy-up federated pyramid: admin distributes the expected-buy-in pool")
class FederatedPyramidPayoutIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;
    private static final BigDecimal BUY_IN = new BigDecimal("20");

    @Autowired private FederatedPyramidService federatedService;
    @Autowired private WalletService walletService;
    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;

    private UUID federationId;
    private UUID w0;
    private UUID w1;

    @BeforeEach
    void setUp() {
        shardRepository.deleteAll();
        federationRepository.deleteAll();
        // 8 / shardSize 4 → 2 shards; expected pool = shardCount(2) × shardSize(4) × buyIn(20) = 160.
        PyramidFederation fed = federatedService.createFederation(
                "Payout " + System.currentTimeMillis(), 8, 4, null, BUY_IN, ASSET, true);
        federationId = fed.getId();
        w0 = fundedWinner(0);
        w1 = fundedWinner(1);
        // Both shards produced a winner; w0 is the grand champion.
        completeShard(0, w0);
        completeShard(1, w1);
        PyramidFederation reload = federationRepository.findById(federationId).orElseThrow();
        reload.complete(w0);
        federationRepository.save(reload);
    }

    private UUID fundedWinner(int i) {
        UUID p = UUID.randomUUID();
        walletService.creditOnChainDeposit(p, ASSET, "tx-" + UUID.randomUUID() + "-" + i, new BigDecimal("100"));
        return p;
    }

    private void completeShard(int index, UUID winner) {
        PyramidFederationShard s = shardRepository.findByFederationIdAndShardIndex(federationId, index).orElseThrow();
        s.markRunning(UUID.randomUUID());
        s.completeWith(winner);
        shardRepository.save(s);
    }

    @Test
    @DisplayName("30% of the 160 pool → 24 per shard winner, remainder 112 → champion; idempotent")
    void distributesExpectedPool() {
        federatedService.distributeFederationPrizes(federationId, 3000);

        // pool 160; shardPool 48 over 2 winners → qualifier 24; championPrize 160 − 48 = 112.
        assertThat(walletService.balance(w0, ASSET)).isEqualByComparingTo("236"); // 100 + 24 + 112
        assertThat(walletService.balance(w1, ASSET)).isEqualByComparingTo("124"); // 100 + 24

        // Idempotent — a re-run pays nothing more (same award keys).
        federatedService.distributeFederationPrizes(federationId, 3000);
        assertThat(walletService.balance(w0, ASSET)).isEqualByComparingTo("236");
        assertThat(walletService.balance(w1, ASSET)).isEqualByComparingTo("124");
    }

    @Test
    @DisplayName("distribution is rejected before the federation is completed")
    void rejectedBeforeCompletion() {
        PyramidFederation fresh = federatedService.createFederation(
                "Fresh " + System.currentTimeMillis(), 8, 4, null, BUY_IN, ASSET, true);
        assertThatThrownBy(() -> federatedService.distributeFederationPrizes(fresh.getId(), 3000))
                .isInstanceOf(IllegalStateException.class);
    }
}
