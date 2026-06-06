package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.CashSeat;
import com.truholdem.model.CashSeatStatus;
import com.truholdem.repository.CashSeatRepository;

@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("CashSeat persistence + seat-session queries")
class CashSeatRepositoryIT {

    @Autowired
    private CashSeatRepository repository;

    @Test
    @DisplayName("persists a seat with stack/buy-in and supports top-up + lifecycle transitions")
    void persistAndLifecycle() {
        repository.deleteAll();
        UUID tableId = UUID.randomUUID();
        UUID hero = UUID.randomUUID();

        CashSeat seat = repository.save(
                new CashSeat(tableId, hero, "Hero", 3, new BigDecimal("10.00")));

        CashSeat reloaded = repository.findById(seat.getId()).orElseThrow();
        assertThat(reloaded.getCashTableId()).isEqualTo(tableId);
        assertThat(reloaded.getPlayerId()).isEqualTo(hero);
        assertThat(reloaded.getSeatNumber()).isEqualTo(3);
        assertThat(reloaded.getStack()).isEqualByComparingTo("10.00");
        assertThat(reloaded.getBuyInTotal()).isEqualByComparingTo("10.00");
        assertThat(reloaded.getStatus()).isEqualTo(CashSeatStatus.ACTIVE);
        assertThat(reloaded.getJoinedAt()).isNotNull();
        assertThat(reloaded.getLeftAt()).isNull();
        assertThat(reloaded.isSeated()).isTrue();

        reloaded.topUp(new BigDecimal("5.00"));
        reloaded.requestLeave();
        repository.save(reloaded);

        CashSeat afterTopUp = repository.findById(seat.getId()).orElseThrow();
        assertThat(afterTopUp.getStack()).isEqualByComparingTo("15.00");
        assertThat(afterTopUp.getBuyInTotal()).isEqualByComparingTo("15.00");
        assertThat(afterTopUp.getStatus()).isEqualTo(CashSeatStatus.LEAVING);
    }

    @Test
    @DisplayName("queries: active seats by table, seat-number occupancy, lookup excludes LEFT seats")
    void seatQueries() {
        repository.deleteAll();
        UUID tableId = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        UUID heroId = UUID.randomUUID();
        CashSeat hero = repository.save(new CashSeat(tableId, heroId, "Hero", 0, new BigDecimal("20.00")));
        repository.save(new CashSeat(tableId, UUID.randomUUID(), "Villain", 1, new BigDecimal("20.00")));
        repository.save(new CashSeat(other, UUID.randomUUID(), "Stranger", 0, new BigDecimal("20.00")));

        assertThat(repository.findByCashTableId(tableId)).hasSize(2);
        assertThat(repository.findByCashTableIdAndStatus(tableId, CashSeatStatus.ACTIVE)).hasSize(2);
        assertThat(repository.countByCashTableIdAndStatusNot(tableId, CashSeatStatus.LEFT)).isEqualTo(2);
        assertThat(repository.existsByCashTableIdAndSeatNumberAndStatusNot(tableId, 0, CashSeatStatus.LEFT))
                .isTrue();
        assertThat(repository.findByCashTableIdAndPlayerIdAndStatusNot(tableId, heroId, CashSeatStatus.LEFT))
                .isPresent();

        // Standing up frees the seat for occupancy / active queries.
        hero.markLeft();
        repository.save(hero);
        assertThat(repository.findById(hero.getId()).orElseThrow().getLeftAt()).isNotNull();
        assertThat(repository.findByCashTableIdAndStatus(tableId, CashSeatStatus.ACTIVE)).hasSize(1);
        assertThat(repository.countByCashTableIdAndStatusNot(tableId, CashSeatStatus.LEFT)).isEqualTo(1);
        assertThat(repository.existsByCashTableIdAndSeatNumberAndStatusNot(tableId, 0, CashSeatStatus.LEFT))
                .isFalse();
        assertThat(repository.findByCashTableIdAndPlayerIdAndStatusNot(tableId, heroId, CashSeatStatus.LEFT))
                .isEmpty();
    }
}
