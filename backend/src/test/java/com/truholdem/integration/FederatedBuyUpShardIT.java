package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.truholdem.model.FederationShardStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.repository.PyramidBuyoutRepository;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.tournament.PyramidBuyoutService;
import com.truholdem.service.wallet.WalletService;

/**
 * Buy-up federated pyramid (slice 1): a shard can open its buy-out window while under-filled (so higher-level
 * seats are buyable), a player buys a guaranteed level-2 seat through the existing buy-up machinery, and the
 * shard then starts + runs to a winner. Buy-up uses the federation buy-in charged at shard seating.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.game.bot-mode=passive",
        "app.tournament.pyramid-default-seats-per-table=2",
        "app.tournament.pyramid-default-hands-per-round=1",
        "app.tournament.federated-max-concurrent-shards=8"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Buy-up federated pyramid: buy a higher-level seat in an under-filled shard, then run it")
class FederatedBuyUpShardIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;
    private static final BigDecimal BUY_IN = new BigDecimal("20");

    @Autowired private FederatedPyramidService federatedService;
    @Autowired private PyramidBuyoutService buyoutService;
    @Autowired private WalletService walletService;
    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;
    @Autowired private PyramidFederationRegistrationRepository registrationRepository;
    @Autowired private PyramidBuyoutRepository buyoutRepository;

    private UUID federationId;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        buyoutRepository.deleteAll();
        registrationRepository.deleteAll();
        shardRepository.deleteAll();
        federationRepository.deleteAll();
        // shardSize 4 / seats 2 → shard pyramid 4→2→1 (level-2 buyable); 8/4 → 2 shards.
        PyramidFederation fed = federatedService.createFederation(
                "BuyUp " + System.currentTimeMillis(), 8, 4, null, BUY_IN, ASSET, true);
        federationId = fed.getId();
        // Under-fill shard 0 with just 2 floor players (leaves level-2 seat 1 — covering floor [2,4) — buyable).
        for (int i = 0; i < 2; i++) {
            UUID p = UUID.randomUUID();
            walletService.creditOnChainDeposit(p, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("300"));
            federatedService.register(federationId, p, "Bot_" + i); // buy-up: not charged yet
            assertThat(walletService.balance(p, ASSET)).isEqualByComparingTo("300");
            if (i == 0) {
                buyer = p;
            }
        }
    }

    @Test
    @DisplayName("opening the buy-out window seats players (charged), then a level-2 seat is bought + the shard runs")
    void buyUpInShard() {
        PyramidFederationShard shard = federatedService.openShardForBuyUp(federationId, 0);
        assertThat(shard.getStatus()).isEqualTo(FederationShardStatus.BUYUP_OPEN);
        UUID childTid = shard.getTournamentId();
        assertThat(childTid).isNotNull();
        // Seating into the child charged the buy-in (300 − 20 = 280).
        assertThat(walletService.balance(buyer, ASSET)).isEqualByComparingTo("280");

        // Level-2 seat 1 (covers floor [2,4)) is above the frontier (2 registered) → buyable; price 2 × 20 = 40.
        assertThat(buyoutService.availableTickets(childTid))
                .anyMatch(t -> t.level() == 2 && t.seatIndex() == 1);
        buyoutService.buySeat(childTid, buyer, 2, 1);
        // base 20 refunded, 40 charged → 280 + 20 − 40 = 260.
        assertThat(walletService.balance(buyer, ASSET)).isEqualByComparingTo("260");
        assertThat(buyoutRepository.findByTournamentId(childTid)).hasSize(1);

        // Close the window → the shard starts (fixed-bracket seating honours the buy-out) → RUNNING.
        // (Running a buy-up field to a winner is the engine's job, covered end-to-end by PyramidBuyUpRunIT.)
        assertThat(federatedService.closeBuyUpAndStart(federationId)).isEqualTo(1);
        PyramidFederationShard running = shardRepository.findByFederationIdAndShardIndex(federationId, 0).orElseThrow();
        assertThat(running.getStatus()).isEqualTo(FederationShardStatus.RUNNING);
        assertThat(running.getTournamentId()).isEqualTo(childTid);
    }
}
