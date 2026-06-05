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
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.PyramidBuyout;
import com.truholdem.model.Tournament;
import com.truholdem.repository.PyramidBuyoutRepository;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.tournament.PyramidBuyoutService;
import com.truholdem.service.wallet.TournamentWalletService;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Buy-up pyramid: seat purchase (price replaces buy-in, sub-tree + guard rules)")
class PyramidBuyoutServiceIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;
    private static final BigDecimal BUY_IN = new BigDecimal("20");

    @Autowired private PyramidBuyoutService buyoutService;
    @Autowired private TournamentWalletService bridge;
    @Autowired private WalletService walletService;
    @Autowired private TournamentService tournamentService;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private TournamentRegistrationRepository registrationRepository;
    @Autowired private PyramidBuyoutRepository buyoutRepository;

    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        buyoutRepository.deleteAll();
        registrationRepository.deleteAll();
        tournamentRepository.deleteAll();
        // 1000 players / 10 seats → levels 1000→100→10→1; level-2 has 100 buyable seats.
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.pyramid("BuyUp " + System.currentTimeMillis(), 1000, 10, 1));
        t.setCryptoBuyInAmount(BUY_IN);
        t.setCryptoBuyInAsset(ASSET);
        t.setPyramidBuyUpEnabled(true);
        tournamentRepository.save(t);
        tournamentId = t.getId();
    }

    private UUID registered(String balance, String name) {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal(balance));
        bridge.buyIn(user, tournamentId, name); // charges the base buy-in + registers
        return user;
    }

    @Test
    @DisplayName("buying a level-2 seat replaces the buy-in: net cost = sub-tree price (10 × buyIn)")
    void buyReplacesBuyIn() {
        UUID player = registered("300", "Alice"); // 300 − 20 buy-in = 280

        PyramidBuyout buyout = buyoutService.buySeat(tournamentId, player, 2, 50);

        assertThat(buyout.getPriceAmount()).isEqualByComparingTo("200"); // 10 × 20
        // base buy-in refunded (+20 → 300), then 200 charged → 100 left. Net spent = 200 = the seat price.
        assertThat(walletService.balance(player, ASSET)).isEqualByComparingTo("100");
        assertThat(buyoutRepository.findByTournamentId(tournamentId)).hasSize(1);
    }

    @Test
    @DisplayName("only a registered player can buy")
    void mustBeRegistered() {
        UUID stranger = UUID.randomUUID();
        walletService.creditOnChainDeposit(stranger, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("300"));
        assertThatThrownBy(() -> buyoutService.buySeat(tournamentId, stranger, 2, 50))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a seat whose sub-pyramid is not empty (below the registration frontier) is not buyable")
    void subTreeMustBeEmpty() {
        for (int i = 0; i < 12; i++) {
            registered("50", "p" + i); // 12 registered → level-1 frontier at seat 12
        }
        UUID buyer = registered("300", "buyer"); // 13 registered now → frontier 13

        // seat 0 covers level-1 [0,10), seat 1 covers [10,20) — both below frontier 13 → not buyable.
        assertThat(buyoutService.availableTickets(tournamentId))
                .noneMatch(t -> t.level() == 2 && (t.seatIndex() == 0 || t.seatIndex() == 1));
        assertThatThrownBy(() -> buyoutService.buySeat(tournamentId, buyer, 2, 0))
                .isInstanceOf(IllegalStateException.class);

        // seat 2 covers [20,30), entirely above the frontier → buyable.
        assertThat(buyoutService.buySeat(tournamentId, buyer, 2, 2).getSeatIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("a buy-out cannot overlap another bought sub-pyramid")
    void noOverlap() {
        UUID a = registered("300", "A");
        UUID b = registered("3000", "B");

        buyoutService.buySeat(tournamentId, a, 2, 50); // level-1 [500,510)
        // level-3 seat 5 covers level-1 [500,600) → overlaps the L2#50 sub-tree → rejected.
        assertThatThrownBy(() -> buyoutService.buySeat(tournamentId, b, 3, 5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a player may buy at most one seat")
    void onePerPlayer() {
        UUID player = registered("3000", "Solo");
        buyoutService.buySeat(tournamentId, player, 2, 50);
        assertThatThrownBy(() -> buyoutService.buySeat(tournamentId, player, 2, 60))
                .isInstanceOf(IllegalStateException.class);
    }
}
