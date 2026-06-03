package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
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
import com.truholdem.domain.event.TournamentCompleted;
import com.truholdem.domain.event.TournamentCompleted.FinishResult;
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.Tournament;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.application.listener.TournamentPayoutListener;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Auto-payout on tournament completion")
class TournamentPayoutListenerIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired
    private TournamentPayoutListener listener;
    @Autowired
    private WalletService walletService;
    @Autowired
    private TournamentService tournamentService;
    @Autowired
    private TournamentRepository tournamentRepository;
    @Autowired
    private TournamentRegistrationRepository registrationRepository;

    private UUID tournamentId;
    private final UUID[] players = new UUID[5];

    @BeforeEach
    void setUp() {
        registrationRepository.deleteAll();
        tournamentRepository.deleteAll();
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.sitAndGo("Payout SNG " + System.currentTimeMillis(), 100));
        t.setCryptoBuyInAmount(new BigDecimal("10"));   // pool = 10 × 5 registrations = 50
        t.setCryptoBuyInAsset(ASSET);
        t.setPayoutStructure(List.of(50, 30, 20)); // top-3 paid
        tournamentRepository.save(t);
        tournamentId = t.getId();

        for (int i = 0; i < 5; i++) {
            players[i] = UUID.randomUUID();
            tournamentService.registerPlayer(tournamentId, players[i], "P" + i);
        }
    }

    private TournamentCompleted completed() {
        return new TournamentCompleted(tournamentId, players[0], "P0", 0, 5, 1, Duration.ZERO, List.of(
                new FinishResult(1, players[0], "P0", 0),
                new FinishResult(2, players[1], "P1", 0),
                new FinishResult(3, players[2], "P2", 0),
                new FinishResult(4, players[3], "P3", 0))); // 4th is out of the money
    }

    @Test
    @DisplayName("each in-the-money finisher is credited their crypto share; out-of-money gets nothing")
    void creditsInTheMoneyFinishers() {
        listener.onTournamentCompleted(completed());

        assertThat(walletService.balance(players[0], ASSET)).as("1st: 50% of 50").isEqualByComparingTo("25");
        assertThat(walletService.balance(players[1], ASSET)).as("2nd: 30% of 50").isEqualByComparingTo("15");
        assertThat(walletService.balance(players[2], ASSET)).as("3rd: 20% of 50").isEqualByComparingTo("10");
        assertThat(walletService.balance(players[3], ASSET)).as("4th: nothing").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("re-firing the completion event does not double-credit")
    void idempotent() {
        listener.onTournamentCompleted(completed());
        listener.onTournamentCompleted(completed());

        assertThat(walletService.balance(players[0], ASSET)).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("a play-money tournament credits nothing")
    void playMoneyNoPayout() {
        Tournament playMoney = tournamentService.createTournament(
                CreateTournamentRequest.sitAndGo("Play SNG " + System.currentTimeMillis(), 100));
        UUID winner = UUID.randomUUID();
        TournamentCompleted event = new TournamentCompleted(playMoney.getId(), winner, "W", 0, 1, 1,
                Duration.ZERO, List.of(new FinishResult(1, winner, "W", 0)));

        listener.onTournamentCompleted(event);

        assertThat(walletService.balance(winner, ASSET)).isEqualByComparingTo("0");
    }
}
