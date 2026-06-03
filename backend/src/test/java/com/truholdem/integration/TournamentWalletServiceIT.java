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
import com.truholdem.model.RegistrationStatus;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.repository.TournamentRegistrationRepository;
import com.truholdem.repository.TournamentRepository;
import com.truholdem.service.TournamentService;
import com.truholdem.service.wallet.TournamentWalletService;
import com.truholdem.service.wallet.WalletExceptions.InsufficientFundsException;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Tournament buy-in bridge (wallet ↔ tournament)")
class TournamentWalletServiceIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired
    private TournamentWalletService bridge;
    @Autowired
    private WalletService walletService;
    @Autowired
    private TournamentService tournamentService;
    @Autowired
    private TournamentRepository tournamentRepository;
    @Autowired
    private TournamentRegistrationRepository registrationRepository;

    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        registrationRepository.deleteAll();
        tournamentRepository.deleteAll();
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.sitAndGo("Buy-in SNG " + System.currentTimeMillis(), 100));
        tournamentId = t.getId();
    }

    private UUID fundedUser(String balance) {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal(balance));
        return user;
    }

    @Test
    @DisplayName("buy-in debits the wallet and registers the player")
    void buyInDebitsAndRegisters() {
        UUID user = fundedUser("50");

        TournamentRegistration reg = bridge.buyIn(user, tournamentId, "Alice", ASSET, new BigDecimal("20"));

        assertThat(reg.getStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("30");
        assertThat(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, user)).isPresent();
    }

    @Test
    @DisplayName("insufficient balance is rejected and the player is NOT registered")
    void insufficientBalanceRejected() {
        UUID user = fundedUser("5");

        assertThatThrownBy(() -> bridge.buyIn(user, tournamentId, "Bob", ASSET, new BigDecimal("20")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("5");
        assertThat(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, user)).isEmpty();
    }

    @Test
    @DisplayName("buy-in is idempotent — a repeat neither double-charges nor double-registers")
    void buyInIdempotent() {
        UUID user = fundedUser("50");

        bridge.buyIn(user, tournamentId, "Carol", ASSET, new BigDecimal("20"));
        bridge.buyIn(user, tournamentId, "Carol", ASSET, new BigDecimal("20"));

        assertThat(walletService.balance(user, ASSET)).as("charged once").isEqualByComparingTo("30");
        assertThat(registrationRepository.findByTournamentId(tournamentId)).hasSize(1);
    }

    @Test
    @DisplayName("payout credits the wallet (idempotent)")
    void payoutCredits() {
        UUID user = fundedUser("10");

        assertThat(bridge.payout(user, tournamentId, ASSET, new BigDecimal("75"))).isTrue();
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("85");
        assertThat(bridge.payout(user, tournamentId, ASSET, new BigDecimal("75"))).as("idempotent").isFalse();
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("85");
    }
}
