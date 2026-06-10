package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
import com.truholdem.dto.CreateTournamentRequest;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.RegistrationStatus;
import com.truholdem.model.Tournament;
import com.truholdem.model.TournamentRegistration;
import com.truholdem.repository.TournamentFeeEntryRepository;
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
    @Autowired
    private TournamentFeeEntryRepository feeEntryRepository;

    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        registrationRepository.deleteAll();
        tournamentRepository.deleteAll();
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.sitAndGo("Buy-in SNG " + System.currentTimeMillis(), 100));
        t.setCryptoBuyInAmount(new BigDecimal("20"));
        t.setCryptoBuyInAsset(ASSET);
        tournamentRepository.save(t);
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

        TournamentRegistration reg = bridge.buyIn(user, tournamentId, "Alice");

        assertThat(reg.getStatus()).isEqualTo(RegistrationStatus.REGISTERED);
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("30");
        assertThat(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, user)).isPresent();
    }

    @Test
    @DisplayName("insufficient balance is rejected and the player is NOT registered")
    void insufficientBalanceRejected() {
        UUID user = fundedUser("5");

        assertThatThrownBy(() -> bridge.buyIn(user, tournamentId, "Bob"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("5");
        assertThat(registrationRepository.findByTournamentIdAndPlayerId(tournamentId, user)).isEmpty();
    }

    @Test
    @DisplayName("buy-in is idempotent — a repeat neither double-charges nor double-registers")
    void buyInIdempotent() {
        UUID user = fundedUser("50");

        bridge.buyIn(user, tournamentId, "Carol");
        bridge.buyIn(user, tournamentId, "Carol");

        assertThat(walletService.balance(user, ASSET)).as("charged once").isEqualByComparingTo("30");
        assertThat(registrationRepository.findByTournamentId(tournamentId)).hasSize(1);
    }

    @Test
    @DisplayName("cancel+refund returns every buy-in and marks the tournament CANCELLED")
    void cancelAndRefund() {
        UUID a = fundedUser("50");
        UUID b = fundedUser("50");
        bridge.buyIn(a, tournamentId, "A"); // 50 → 30
        bridge.buyIn(b, tournamentId, "B"); // 50 → 30

        int refunded = bridge.cancelAndRefund(tournamentId);

        assertThat(refunded).isEqualTo(2);
        assertThat(walletService.balance(a, ASSET)).isEqualByComparingTo("50");
        assertThat(walletService.balance(b, ASSET)).isEqualByComparingTo("50");
        assertThat(tournamentRepository.findById(tournamentId).orElseThrow().getStatus())
                .isEqualTo(com.truholdem.model.TournamentStatus.CANCELLED);
    }

    @Test
    @DisplayName("refund is idempotent per (tournament, user) — a repeat does not double-credit")
    void refundIdempotent() {
        UUID user = fundedUser("30");
        String key = "trefund:" + tournamentId + ":" + user;

        assertThat(walletService.refundBuyIn(user, ASSET, new BigDecimal("20"), key)).isTrue();
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("50");
        assertThat(walletService.refundBuyIn(user, ASSET, new BigDecimal("20"), key)).as("idempotent").isFalse();
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("50");
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

    @Test
    @DisplayName("a fee tournament pays out the NET pool and records the house commission as revenue")
    void payoutOnCompletionAppliesFeeAndRecordsRevenue() {
        // 10% fee, buy-in 20, 4 players → gross 80, fee 8, net pool 72 (split 50/30/20).
        Tournament t = tournamentService.createTournament(
                CreateTournamentRequest.freezeout("Fee SNG " + System.currentTimeMillis(), 0, 9));
        t.setCryptoBuyInAmount(new BigDecimal("20"));
        t.setCryptoBuyInAsset(ASSET);
        t.setFeeBasisPoints(1000);
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();
        UUID p4 = UUID.randomUUID();
        for (UUID p : List.of(p1, p2, p3, p4)) {
            t.registerPlayer(p, "n");
        }
        tournamentRepository.save(t);
        UUID id = t.getId();

        int credited = bridge.payoutOnCompletion(id, List.of(
                new TournamentCompleted.FinishResult(1, p1, "P1", 0),
                new TournamentCompleted.FinishResult(2, p2, "P2", 0),
                new TournamentCompleted.FinishResult(3, p3, "P3", 0)));

        assertThat(credited).isEqualTo(3);
        assertThat(walletService.balance(p1, ASSET)).as("50% of net 72").isEqualByComparingTo("36");
        assertThat(walletService.balance(p2, ASSET)).as("30% of net 72").isEqualByComparingTo("21.6");
        assertThat(walletService.balance(p3, ASSET)).as("20% of net 72").isEqualByComparingTo("14.4");

        assertThat(feeEntryRepository.findByIdempotencyKey("tfee:" + id)).isPresent();
        assertThat(feeEntryRepository.totalFeeForAsset(ASSET)).as("house fee = 10% of 80").isEqualByComparingTo("8");

        // Idempotent: a re-run neither double-pays nor double-records the fee.
        bridge.payoutOnCompletion(id, List.of(new TournamentCompleted.FinishResult(1, p1, "P1", 0)));
        assertThat(walletService.balance(p1, ASSET)).isEqualByComparingTo("36");
        assertThat(feeEntryRepository.findBySourceId(id)).hasSize(1);
    }
}
