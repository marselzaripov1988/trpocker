package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.repository.PyramidFederationFinalBuyoutRepository;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.wallet.WalletService;

/**
 * Buy-up federated pyramid slice 2: a player buys a guaranteed seat among the finalists (bypassing the shards)
 * by closing an empty shard for a whole shard's buy-ins; the final is then seeded from shard winners + buyers.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.game.bot-mode=passive",
        "app.tournament.pyramid-default-seats-per-table=2",
        "app.tournament.pyramid-default-hands-per-round=1"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Buy-up federated pyramid: buy a guaranteed finalist seat by closing an empty shard")
class FederatedFinalBuyoutIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;
    private static final BigDecimal BUY_IN = new BigDecimal("20");

    @Autowired private FederatedPyramidService federatedService;
    @Autowired private TournamentService tournamentService;
    @Autowired private WalletService walletService;
    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;
    @Autowired private PyramidFederationFinalBuyoutRepository finalBuyoutRepository;
    @Autowired private PyramidFederationRegistrationRepository registrationRepository;

    private UUID federationId;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        finalBuyoutRepository.deleteAll();
        registrationRepository.deleteAll();
        shardRepository.deleteAll();
        federationRepository.deleteAll();
        // 8 / shardSize 4 → 2 shards; final-seat price = 4 × 20 = 80.
        PyramidFederation fed = federatedService.createFederation(
                "FinalBuy " + System.currentTimeMillis(), 8, 4, null, BUY_IN, ASSET, true);
        federationId = fed.getId();
        buyer = UUID.randomUUID();
        walletService.creditOnChainDeposit(buyer, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("300"));
    }

    @Test
    @DisplayName("buying a final seat charges a shard's buy-ins, closes that shard, and removes it from offers")
    void buyFinalSeatClosesShard() {
        assertThat(federatedService.availableFinalSeats(federationId)).anyMatch(t -> t.shardIndex() == 1);

        federatedService.buyFinalSeat(federationId, buyer, 1);

        assertThat(walletService.balance(buyer, ASSET)).isEqualByComparingTo("220"); // 300 − 80
        assertThat(finalBuyoutRepository.findByFederationId(federationId)).hasSize(1);
        assertThat(shardRepository.findByFederationIdAndShardIndex(federationId, 1).orElseThrow().getStatus())
                .isEqualTo(FederationShardStatus.CANCELLED);
        assertThat(federatedService.availableFinalSeats(federationId)).noneMatch(t -> t.shardIndex() == 1);
    }

    @Test
    @DisplayName("once shards are resolved (a winner + a final buyer), the final is seeded from both")
    void finalSeededFromWinnersAndBuyers() {
        // Shard 0 produced a winner; close shard 1 with a final-seat buy-out → field resolved.
        PyramidFederationShard s0 = shardRepository.findByFederationIdAndShardIndex(federationId, 0).orElseThrow();
        s0.markRunning(UUID.randomUUID());
        s0.completeWith(UUID.randomUUID());
        shardRepository.save(s0);
        PyramidFederation fed = federationRepository.findById(federationId).orElseThrow();
        fed.markShardsRunning();
        federationRepository.save(fed);

        federatedService.buyFinalSeat(federationId, buyer, 1);

        assertThat(federationRepository.findById(federationId).orElseThrow().getStatus())
                .isEqualTo(FederationStatus.AWAITING_FINAL);

        federatedService.scheduleFinal(federationId, Instant.now().plus(2, ChronoUnit.HOURS));
        PyramidFederation running = federatedService.startFinal(federationId);

        assertThat(running.getStatus()).isEqualTo(FederationStatus.FINAL_RUNNING);
        // Final seeded with the shard-0 winner + the final-seat buyer.
        assertThat(tournamentService.registeredCount(running.getFinalTournamentId())).isEqualTo(2);
    }
}
