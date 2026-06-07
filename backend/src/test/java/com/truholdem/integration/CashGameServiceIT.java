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
import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.model.CashSeat;
import com.truholdem.model.CashSeatStatus;
import com.truholdem.model.CashTable;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.repository.CashRakeEntryRepository;
import com.truholdem.repository.CashSeatRepository;
import com.truholdem.repository.CashTableRepository;
import com.truholdem.service.CashGameService;
import com.truholdem.service.CashRakeService;
import com.truholdem.service.wallet.CashGameWalletService;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Cash hand on the aggregate kernel: rake at showdown + settle stacks + deferred cash-out")
class CashGameServiceIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired private CashGameService cashGameService;
    @Autowired private CashGameWalletService walletBridge;
    @Autowired private WalletService walletService;
    @Autowired private CashRakeService rakeService;
    @Autowired private CashTableRepository cashTableRepository;
    @Autowired private CashSeatRepository cashSeatRepository;
    @Autowired private CashRakeEntryRepository rakeRepository;

    private UUID tableId;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        rakeRepository.deleteAll();
        cashSeatRepository.deleteAll();
        cashTableRepository.deleteAll();
        CashTable table = cashTableRepository.save(new CashTable("Micro " + System.currentTimeMillis(),
                ASSET, new BigDecimal("0.05"), new BigDecimal("0.10"),
                new BigDecimal("2.00"), new BigDecimal("20.00"), 6, 500, new BigDecimal("1.00")));
        tableId = table.getId();
        alice = fundedUser("50");
        bob = fundedUser("50");
        walletBridge.buyIn(alice, tableId, "Alice", new BigDecimal("10.00")); // 50 -> 40, stack 10.00
        walletBridge.buyIn(bob, tableId, "Bob", new BigDecimal("10.00"));      // 50 -> 40, stack 10.00
    }

    private UUID fundedUser(String balance) {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal(balance));
        return user;
    }

    private BigDecimal totalStacks() {
        return cashSeatRepository.findByCashTableId(tableId).stream()
                .map(CashSeat::getStack)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Drive a passive check/call hand to showdown (5 community cards). */
    private void playToShowdown(PokerGame game) {
        int guard = 0;
        while (game.getPhase() != GamePhase.FINISHED && guard++ < 200) {
            Player current = game.getCurrentPlayer();
            if (current == null) {
                break;
            }
            try {
                game.executeAction(current.getId(), PlayerAction.CHECK, null);
            } catch (RuntimeException notCheckable) {
                game.executeAction(current.getId(), PlayerAction.CALL, null);
            }
        }
    }

    @Test
    @DisplayName("uncontested pre-flop fold takes no rake; stacks are conserved and written back")
    void uncontestedFoldNoRake() {
        PokerGame game = cashGameService.startHand(tableId);
        Player first = game.getCurrentPlayer();
        game.executeAction(first.getId(), PlayerAction.FOLD, null);
        assertThat(game.getPhase()).isEqualTo(GamePhase.FINISHED);

        CashGameService.CashHandResult result = cashGameService.settleHand(tableId, game);

        assertThat(result.totalRake()).isEqualByComparingTo("0");
        assertThat(rakeService.houseRevenue(tableId)).isEqualByComparingTo("0");
        assertThat(totalStacks()).as("chips conserved, no rake").isEqualByComparingTo("20.00");
        // someone won the blinds: the two stacks are no longer both exactly 10.00
        assertThat(cashSeatRepository.findByCashTableId(tableId))
                .anyMatch(s -> s.getStack().compareTo(new BigDecimal("10.00")) != 0);
    }

    @Test
    @DisplayName("a contested showdown is raked: house revenue accrues and total stacks drop by the rake")
    void contestedShowdownRaked() {
        PokerGame game = cashGameService.startHand(tableId);
        playToShowdown(game);
        assertThat(game.getPhase()).isEqualTo(GamePhase.FINISHED);
        assertThat(game.getCommunityCards()).hasSize(5);

        CashGameService.CashHandResult result = cashGameService.settleHand(tableId, game);

        // 0.20 pot, 5% = 0.01 (under the 1.00 cap); a split pot rakes each half the same total.
        assertThat(result.totalRake()).isEqualByComparingTo("0.01");
        assertThat(rakeService.houseRevenue(tableId)).isEqualByComparingTo("0.01");
        assertThat(totalStacks()).as("20.00 in play minus 0.01 rake").isEqualByComparingTo("19.99");
    }

    @Test
    @DisplayName("a seat that asked to leave during the hand is cashed out on settle")
    void leavingSeatCashedOutOnSettle() {
        PokerGame game = cashGameService.startHand(tableId);
        Player first = game.getCurrentPlayer();
        game.executeAction(first.getId(), PlayerAction.FOLD, null);

        // Alice asks to leave mid-hand; she is settled + cashed out once the hand finishes.
        walletBridge.requestLeave(alice, tableId);

        CashGameService.CashHandResult result = cashGameService.settleHand(tableId, game);

        assertThat(result.cashedOut()).contains(alice).doesNotContain(bob);
        CashSeat aliceSeat = cashSeatRepository.findByCashTableId(tableId).stream()
                .filter(s -> s.getPlayerId().equals(alice)).findFirst().orElseThrow();
        assertThat(aliceSeat.getStatus()).isEqualTo(CashSeatStatus.LEFT);
        // she had 40 in the wallet after buying in; the cash-out returns her final stack.
        assertThat(walletService.balance(alice, ASSET))
                .isEqualByComparingTo(new BigDecimal("40").add(aliceSeat.getStack()));
        // Bob stays seated.
        assertThat(cashSeatRepository.findByCashTableId(tableId).stream()
                .filter(s -> s.getPlayerId().equals(bob)).findFirst().orElseThrow().isSeated()).isTrue();
    }
}
