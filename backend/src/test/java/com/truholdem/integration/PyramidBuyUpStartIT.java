package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
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
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentTable;
import com.truholdem.repository.PyramidBuyoutRepository;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.repository.TournamentTableRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.PyramidBuyoutService;
import com.truholdem.service.wallet.TournamentWalletService;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Buy-up pyramid: fixed-bracket seating at start (floor skips closed sub-trees; buyers wait)")
class PyramidBuyUpStartIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired private TournamentService tournamentService;
    @Autowired private TournamentWalletService bridge;
    @Autowired private WalletService walletService;
    @Autowired private PyramidBuyoutService buyoutService;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private TournamentRegistrationRepository registrationRepository;
    @Autowired private TournamentTableRepository tableRepository;
    @Autowired private PyramidBuyoutRepository buyoutRepository;

    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        buyoutRepository.deleteAll();
        registrationRepository.deleteAll();
        tournamentRepository.deleteAll();
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.pyramid("BuyUpStart " + System.currentTimeMillis(), 1000, 10, 1));
        t.setCryptoBuyInAmount(new BigDecimal("20"));
        t.setCryptoBuyInAsset(ASSET);
        t.setPyramidBuyUpEnabled(true);
        tournamentRepository.save(t);
        tournamentId = t.getId();
    }

    private UUID registered(String name) {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("3000"));
        bridge.buyIn(user, tournamentId, name);
        return user;
    }

    @Test
    @DisplayName("floor (non-buyers) seated on level 1; the buyer is elevated, not on the floor")
    void seatsFloorAndElevatesBuyer() {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            players.add(registered("p" + i));
        }
        // Buy-outs must be above the registration frontier (the buyer is registered), so a high seat:
        UUID buyer = players.get(0);
        buyoutService.buySeat(tournamentId, buyer, 2, 50); // far above the floor → table index 50 closed

        tournamentService.startTournament(tournamentId);

        List<TournamentTable> tables = tableRepository.findActiveTablesByTournament(tournamentId);
        List<UUID> seated = tables.stream().flatMap(t -> t.getPlayerIds().stream()).toList();

        assertThat(seated).as("14 floor players seated (15 registered − 1 buyer)").hasSize(14);
        assertThat(seated).as("buyer is elevated, not on the floor").doesNotContain(buyer);
        assertThat(tables).allSatisfy(t -> assertThat(t.getBracketLevel()).isEqualTo(1));
        // Floor fills bottom-up: table #1 (seats 0..9) + table #2 (seats 10..13); no high floor table created.
        assertThat(tables).anyMatch(t -> t.getTableNumber() == 1);
        assertThat(tables).noneMatch(t -> t.getTableNumber() == 51); // the bought (level-2) table isn't a floor table
    }
}
