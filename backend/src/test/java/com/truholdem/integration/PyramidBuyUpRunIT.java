package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentStatus;
import com.truholdem.repository.PyramidBuyoutRepository;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.PyramidBuyoutService;
import com.truholdem.service.tournament.PyramidTournamentService;
import com.truholdem.service.tournament.PyramidTournamentService.PyramidRunResult;
import com.truholdem.service.wallet.TournamentWalletService;
import com.truholdem.service.wallet.WalletService;

/**
 * Buy-up pyramid run-to-completion: floor plays level 1, the level-2 buyers enter at level 2, and exactly one
 * champion emerges. Players are real-money (funded + bridge buy-in) and bot-named so the engine can play them.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.payments.enabled=true",
        "app.game.bot-mode=passive",
        "app.tournament.pyramid-default-hands-per-round=1",
        "app.tournament.pyramid-default-seats-per-table=10"
})
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Buy-up pyramid — run to completion (buyers enter at level 2)")
class PyramidBuyUpRunIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired private TournamentService tournamentService;
    @Autowired private TournamentWalletService bridge;
    @Autowired private WalletService walletService;
    @Autowired private PyramidBuyoutService buyoutService;
    @Autowired private PyramidTournamentService pyramidService;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private TournamentRegistrationRepository registrationRepository;
    @Autowired private TournamentTableRepository tableRepository;
    @Autowired private PyramidBuyoutRepository buyoutRepository;

    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        buyoutRepository.deleteAll();
        registrationRepository.deleteAll();
        tableRepository.deleteAll();
        tournamentRepository.deleteAll();
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.pyramid("BuyUpRun " + System.currentTimeMillis(), 100, 10, 1));
        t.setCryptoBuyInAmount(new BigDecimal("20"));
        t.setCryptoBuyInAsset(ASSET);
        t.setPyramidBuyUpEnabled(true);
        tournamentRepository.save(t);
        tournamentId = t.getId();
    }

    private UUID register(int i, String balance) {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal(balance));
        bridge.buyIn(user, tournamentId, "Bot_" + i); // bot-named so the engine can auto-play
        return user;
    }

    @Test
    @Timeout(300)
    @DisplayName("level-2 buyers join the final table and one champion is crowned")
    void runsToChampionWithBuyers() {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            players.add(register(i, "50"));
        }
        // Two buy level-2 seats above the registration frontier (52 registered → seat index ≥ 6).
        UUID buyerA = register(50, "300");
        UUID buyerB = register(51, "300");
        buyoutService.buySeat(tournamentId, buyerA, 2, 6);
        buyoutService.buySeat(tournamentId, buyerB, 2, 7);

        PyramidRunResult result = pyramidService.runToCompletion(tournamentId);

        assertThat(result.finalStatus()).isEqualTo(TournamentStatus.COMPLETED);
        assertThat(result.championId()).isNotNull();
        // Everyone (incl. the buyers) was resolved → exactly one active remains is impossible after completion.
        assertThat(registrationRepository.countActiveByTournamentId(tournamentId)).isLessThanOrEqualTo(1);
        // The buyers did enter (no longer registered-but-unplayed): they are either champion or eliminated.
        assertThat(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, buyerA).orElseThrow()
                .getFinishPosition() != null
                || buyerA.equals(result.championId())).isTrue();
    }
}
