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
import com.truholdem.domain.aggregate.PokerGame;
import com.truholdem.model.CashSeat;
import com.truholdem.model.CashTable;
import com.truholdem.model.CryptoAsset;
import com.truholdem.model.GamePhase;
import com.truholdem.model.Player;
import com.truholdem.model.PlayerAction;
import com.truholdem.repository.CashRakeEntryRepository;
import com.truholdem.repository.CashSeatRepository;
import com.truholdem.repository.CashTableRepository;
import com.truholdem.repository.GameRepository;
import com.truholdem.service.CashGameService;
import com.truholdem.service.CashGameService.CashActResult;
import com.truholdem.service.CashRakeService;
import com.truholdem.service.wallet.CashGameWalletService;
import com.truholdem.service.wallet.WalletService;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.payments.enabled=true" })
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Cash hand persistence: live hand survives across act() calls (reloaded from the DB)")
class CashHandPersistenceIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired private CashGameService cashGameService;
    @Autowired private CashGameWalletService walletBridge;
    @Autowired private WalletService walletService;
    @Autowired private CashRakeService rakeService;
    @Autowired private CashTableRepository cashTableRepository;
    @Autowired private CashSeatRepository cashSeatRepository;
    @Autowired private CashRakeEntryRepository rakeRepository;
    @Autowired private GameRepository gameRepository;

    private UUID tableId;

    @BeforeEach
    void setUp() {
        rakeRepository.deleteAll();
        cashSeatRepository.deleteAll();
        cashTableRepository.deleteAll();
        CashTable table = cashTableRepository.save(new CashTable("Persist " + System.currentTimeMillis(),
                ASSET, new BigDecimal("0.05"), new BigDecimal("0.10"),
                new BigDecimal("2.00"), new BigDecimal("20.00"), 6, 500, new BigDecimal("1.00")));
        tableId = table.getId();
        walletBridge.buyIn(fundedUser(), tableId, "Alice", new BigDecimal("10.00"));
        walletBridge.buyIn(fundedUser(), tableId, "Bob", new BigDecimal("10.00"));
    }

    private UUID fundedUser() {
        UUID user = UUID.randomUUID();
        walletService.creditOnChainDeposit(user, ASSET, "tx-" + UUID.randomUUID(), new BigDecimal("50"));
        return user;
    }

    private BigDecimal totalStacks() {
        return cashSeatRepository.findByCashTableId(tableId).stream()
                .map(CashSeat::getStack).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("openHand persists the hand (hole cards included) and links the table")
    void openHandPersistsWithHoleCards() {
        UUID gameId = cashGameService.openHand(tableId);

        assertThat(cashTableRepository.findById(tableId).orElseThrow().getCurrentGameId()).isEqualTo(gameId);
        assertThat(gameRepository.existsById(gameId)).isTrue();

        // Reconstituted purely from the DB: each player kept their two hole cards (JPA persists them even
        // though they are @JsonIgnore — a JSON snapshot would have lost them).
        PokerGame reloaded = cashGameService.peekHand(tableId);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getPhase()).isEqualTo(GamePhase.PRE_FLOP);
        assertThat(reloaded.getPlayers()).hasSize(2)
                .allSatisfy(p -> assertThat(p.getHand()).hasSize(2));

        assertThatThrownBy(() -> cashGameService.openHand(tableId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has a live hand");
    }

    @Test
    @DisplayName("a hand driven entirely through act() (DB reloaded each call) reaches showdown, rakes, and frees the table")
    void drivenAcrossCallsSettlesAndFrees() {
        UUID gameId = cashGameService.openHand(tableId);

        CashActResult last = null;
        int guard = 0;
        while (guard++ < 200) {
            PokerGame view = cashGameService.peekHand(tableId);
            if (view == null) {
                break; // settled + table freed
            }
            Player current = view.getCurrentPlayer();
            if (current == null) {
                break;
            }
            UUID actorId = current.getId();
            try {
                last = cashGameService.act(tableId, actorId, PlayerAction.CHECK, null);
            } catch (RuntimeException notCheckable) {
                last = cashGameService.act(tableId, actorId, PlayerAction.CALL, null);
            }
            if (last.handComplete()) {
                break;
            }
        }

        assertThat(last).isNotNull();
        assertThat(last.handComplete()).isTrue();
        assertThat(last.totalRake()).isEqualByComparingTo("0.01"); // 0.20 pot, 5% capped at 1.00
        assertThat(rakeService.houseRevenue(tableId)).isEqualByComparingTo("0.01");
        assertThat(totalStacks()).isEqualByComparingTo("19.99");

        // table freed + the live-hand row removed
        assertThat(cashTableRepository.findById(tableId).orElseThrow().getCurrentGameId()).isNull();
        assertThat(gameRepository.existsById(gameId)).isFalse();
        assertThat(cashGameService.peekHand(tableId)).isNull();
    }
}
