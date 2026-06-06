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
import com.truholdem.model.CashSeat;
import com.truholdem.model.CashSeatStatus;
import com.truholdem.model.CashTable;
import com.truholdem.model.CryptoAsset;
import com.truholdem.repository.CashSeatRepository;
import com.truholdem.repository.CashTableRepository;
import com.truholdem.service.wallet.CashGameWalletService;
import com.truholdem.service.wallet.WalletExceptions.InsufficientFundsException;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Cash buy-in bridge (wallet ↔ cash table)")
class CashGameWalletServiceIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired
    private CashGameWalletService bridge;
    @Autowired
    private WalletService walletService;
    @Autowired
    private CashTableRepository cashTableRepository;
    @Autowired
    private CashSeatRepository cashSeatRepository;

    private UUID tableId;

    @BeforeEach
    void setUp() {
        cashSeatRepository.deleteAll();
        cashTableRepository.deleteAll();
        CashTable table = cashTableRepository.save(new CashTable("Micro USDT " + System.currentTimeMillis(),
                ASSET, new BigDecimal("0.05"), new BigDecimal("0.10"),
                new BigDecimal("2.00"), new BigDecimal("20.00"), 2, 500, new BigDecimal("1.00")));
        tableId = table.getId();
    }

    private UUID fundedUser(String balance) {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal(balance));
        return user;
    }

    @Test
    @DisplayName("buy-in debits the wallet and seats the player with a stack")
    void buyInDebitsAndSeats() {
        UUID user = fundedUser("50");

        CashSeat seat = bridge.buyIn(user, tableId, "Alice", new BigDecimal("10.00"));

        assertThat(seat.getStatus()).isEqualTo(CashSeatStatus.ACTIVE);
        assertThat(seat.getSeatNumber()).isEqualTo(0);
        assertThat(seat.getStack()).isEqualByComparingTo("10.00");
        assertThat(seat.getBuyInTotal()).isEqualByComparingTo("10.00");
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("40.00");
        assertThat(cashSeatRepository.findByCashTableIdAndPlayerIdAndStatusNot(tableId, user, CashSeatStatus.LEFT))
                .isPresent();
    }

    @Test
    @DisplayName("insufficient balance is rejected and NO seat is persisted")
    void insufficientBalanceRejected() {
        UUID user = fundedUser("5");

        assertThatThrownBy(() -> bridge.buyIn(user, tableId, "Bob", new BigDecimal("10.00")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("5");
        assertThat(cashSeatRepository.findByCashTableId(tableId)).isEmpty();
    }

    @Test
    @DisplayName("a buy-in outside the table's [min, max] range is rejected")
    void outOfRangeRejected() {
        UUID user = fundedUser("50");

        assertThatThrownBy(() -> bridge.buyIn(user, tableId, "Lo", new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bridge.buyIn(user, tableId, "Hi", new BigDecimal("25.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cashSeatRepository.findByCashTableId(tableId)).isEmpty();
    }

    @Test
    @DisplayName("seats are assigned lowest-free; a player may hold only one live seat per table")
    void seatAssignmentAndSingleSeat() {
        UUID alice = fundedUser("50");
        UUID bob = fundedUser("50");

        CashSeat a = bridge.buyIn(alice, tableId, "Alice", new BigDecimal("10.00"));
        CashSeat b = bridge.buyIn(bob, tableId, "Bob", new BigDecimal("10.00"));
        assertThat(a.getSeatNumber()).isEqualTo(0);
        assertThat(b.getSeatNumber()).isEqualTo(1);

        assertThatThrownBy(() -> bridge.buyIn(alice, tableId, "Alice", new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already holds a seat");
    }

    @Test
    @DisplayName("a full table rejects further buy-ins")
    void fullTableRejected() {
        bridge.buyIn(fundedUser("50"), tableId, "A", new BigDecimal("10.00"));
        bridge.buyIn(fundedUser("50"), tableId, "B", new BigDecimal("10.00"));

        assertThatThrownBy(() -> bridge.buyIn(fundedUser("50"), tableId, "C", new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("full");
    }

    @Test
    @DisplayName("a closed table rejects buy-ins")
    void closedTableRejected() {
        CashTable table = cashTableRepository.findById(tableId).orElseThrow();
        table.setActive(false);
        cashTableRepository.save(table);

        assertThatThrownBy(() -> bridge.buyIn(fundedUser("50"), tableId, "A", new BigDecimal("10.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("re-sitting after standing up is a fresh buy-in (new seat, charged again)")
    void reSitAfterLeaving() {
        UUID user = fundedUser("50");

        CashSeat first = bridge.buyIn(user, tableId, "Alice", new BigDecimal("10.00"));
        first.markLeft();
        cashSeatRepository.save(first);

        CashSeat second = bridge.buyIn(user, tableId, "Alice", new BigDecimal("10.00"));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getSeatNumber()).isEqualTo(0); // seat 0 freed when first left
        assertThat(walletService.balance(user, ASSET)).as("charged twice").isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("cash-out credits the remaining stack, frees the seat, and is idempotent")
    void cashOutCreditsAndFreesSeat() {
        UUID user = fundedUser("50");
        CashSeat seat = bridge.buyIn(user, tableId, "Alice", new BigDecimal("10.00")); // 50 -> 40

        // Simulate a winning session: the engine raised the stack (slice 6 will do this for real).
        seat.setStack(new BigDecimal("17.50"));
        cashSeatRepository.save(seat);

        BigDecimal credited = bridge.cashOut(user, tableId);

        assertThat(credited).isEqualByComparingTo("17.50");
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("57.50"); // 40 + 17.50
        assertThat(cashSeatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(CashSeatStatus.LEFT);
        assertThat(cashSeatRepository.findByCashTableIdAndPlayerIdAndStatusNot(tableId, user, CashSeatStatus.LEFT))
                .isEmpty();

        // Idempotent: a second cash-out (no live seat) is a no-op returning zero, balance unchanged.
        assertThat(bridge.cashOut(user, tableId)).isEqualByComparingTo("0");
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("57.50");
    }

    @Test
    @DisplayName("requestLeave marks the seat LEAVING; a later cash-out still settles it")
    void requestLeaveThenCashOut() {
        UUID user = fundedUser("50");
        bridge.buyIn(user, tableId, "Alice", new BigDecimal("10.00"));

        CashSeat leaving = bridge.requestLeave(user, tableId);
        assertThat(leaving.getStatus()).isEqualTo(CashSeatStatus.LEAVING);

        assertThat(bridge.cashOut(user, tableId)).isEqualByComparingTo("10.00");
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("a busted (zero) stack frees the seat with no wallet credit")
    void cashOutBustedStack() {
        UUID user = fundedUser("50");
        CashSeat seat = bridge.buyIn(user, tableId, "Alice", new BigDecimal("10.00")); // 50 -> 40
        seat.setStack(BigDecimal.ZERO);
        cashSeatRepository.save(seat);

        assertThat(bridge.cashOut(user, tableId)).isEqualByComparingTo("0");
        assertThat(walletService.balance(user, ASSET)).isEqualByComparingTo("40.00"); // no credit back
        assertThat(cashSeatRepository.findById(seat.getId()).orElseThrow().getStatus())
                .isEqualTo(CashSeatStatus.LEFT);
    }
}
