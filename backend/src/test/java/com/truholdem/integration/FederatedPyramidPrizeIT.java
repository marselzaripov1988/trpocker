package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.truholdem.model.FederationStatus;
import com.truholdem.model.PyramidFederation;
import com.truholdem.model.PyramidFederationShard;
import com.truholdem.repository.PyramidFederationRegistrationRepository;
import com.truholdem.repository.PyramidFederationRepository;
import com.truholdem.repository.PyramidFederationShardRepository;
import com.truholdem.service.tournament.FederatedPyramidService;
import com.truholdem.service.wallet.WalletService;

/**
 * Federated pyramid slice 7: a real-money federation charges the buy-in on registration and pays the pool out
 * to shard winners (qualifier) + the grand champion. seats=2 keeps an 8-player / 4-shard federation valid.
 * With buyIn=20 and shardPrizeBps=3000: pool=160, shardPool=48, qualifier=12, championPrize=112.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.game.bot-mode=passive",
        "app.tournament.pyramid-default-seats-per-table=2",
        "app.tournament.pyramid-default-hands-per-round=1",
        "app.tournament.federated-max-concurrent-shards=2",
        "app.tournament.federated-shard-prize-bps=3000"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Federated pyramid: real-money buy-in + prize distribution (shard winners + champion)")
class FederatedPyramidPrizeIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;
    private static final BigDecimal BUY_IN = new BigDecimal("20");
    private static final BigDecimal FUND = new BigDecimal("100");

    @Autowired private FederatedPyramidService federatedService;
    @Autowired private WalletService walletService;
    @Autowired private PyramidFederationRepository federationRepository;
    @Autowired private PyramidFederationShardRepository shardRepository;
    @Autowired private PyramidFederationRegistrationRepository registrationRepository;

    private UUID federationId;
    private final List<UUID> players = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registrationRepository.deleteAll();
        shardRepository.deleteAll();
        federationRepository.deleteAll();
        players.clear();
        PyramidFederation fed = federatedService.createFederation(
                "Real " + System.currentTimeMillis(), 8, 2, null, BUY_IN, ASSET);
        federationId = fed.getId();
        for (int i = 0; i < 8; i++) {
            UUID p = UUID.randomUUID();
            walletService.creditOnChainDeposit(p, ASSET, "tx-" + UUID.randomUUID(), FUND);
            federatedService.register(federationId, p, "Bot_" + i); // charges 20 → balance 80
            players.add(p);
        }
    }

    @Test
    @DisplayName("buy-in is charged on registration; the pool is fully redistributed to winners + champion")
    void chargesAndPaysOut() {
        // Each player charged the buy-in.
        for (UUID p : players) {
            assertThat(walletService.balance(p, ASSET)).isEqualByComparingTo("80");
        }

        federatedService.drainShards(federationId);
        federatedService.scheduleFinal(federationId, Instant.now().plus(2, ChronoUnit.HOURS));
        federatedService.startFinal(federationId);
        federatedService.runFinalToChampion(federationId);

        PyramidFederation done = federationRepository.findById(federationId).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(FederationStatus.COMPLETED);
        UUID champion = done.getChampionPlayerId();
        var winners = shardRepository.findByFederationIdOrderByShardIndexAsc(federationId).stream()
                .map(PyramidFederationShard::getWinnerPlayerId).collect(Collectors.toSet());

        // pool 160; shardPool 48 over 4 winners → qualifier 12; championPrize 160-48 = 112.
        for (UUID p : players) {
            BigDecimal bal = walletService.balance(p, ASSET);
            if (p.equals(champion)) {
                assertThat(bal).isEqualByComparingTo("204");   // 100 - 20 + 12 + 112
            } else if (winners.contains(p)) {
                assertThat(bal).isEqualByComparingTo("92");     // 100 - 20 + 12
            } else {
                assertThat(bal).isEqualByComparingTo("80");     // 100 - 20 (lost the buy-in)
            }
        }

        // Pool conservation: buy-ins fully redistributed → total balance unchanged at 8 × 100.
        BigDecimal total = players.stream()
                .map(p -> walletService.balance(p, ASSET))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("800");
    }
}
