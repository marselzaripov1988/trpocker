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

import com.truholdem.config.TestConfig;
import com.truholdem.config.TestSecurityConfig;
import com.truholdem.model.CashTable;
import com.truholdem.model.CryptoAsset;
import com.truholdem.repository.CashRakeEntryRepository;
import com.truholdem.repository.CashTableRepository;
import com.truholdem.service.CashRakeService;

@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("Cash rake: computation (bps/cap/no-flop-no-drop) + idempotent house revenue")
class CashRakeServiceIT {

    private static final CryptoAsset ASSET = CryptoAsset.USDT_TRC20;

    @Autowired
    private CashRakeService rakeService;
    @Autowired
    private CashTableRepository cashTableRepository;
    @Autowired
    private CashRakeEntryRepository rakeRepository;

    private CashTable table; // 5% rake, cap 1.00

    @BeforeEach
    void setUp() {
        rakeRepository.deleteAll();
        cashTableRepository.deleteAll();
        table = cashTableRepository.save(new CashTable("Rake USDT " + System.currentTimeMillis(),
                ASSET, new BigDecimal("0.05"), new BigDecimal("0.10"),
                new BigDecimal("2.00"), new BigDecimal("20.00"), 6, 500, new BigDecimal("1.00")));
    }

    @Test
    @DisplayName("computeRake applies bps, the cap, and no-flop-no-drop")
    void computeRakeRules() {
        // 5% of 10.00 = 0.50, under the 1.00 cap.
        assertThat(rakeService.computeRake(new BigDecimal("10.00"), true, 500, new BigDecimal("1.00")))
                .isEqualByComparingTo("0.50");
        // 5% of 100.00 = 5.00, clamped to the 1.00 cap.
        assertThat(rakeService.computeRake(new BigDecimal("100.00"), true, 500, new BigDecimal("1.00")))
                .isEqualByComparingTo("1.00");
        // uncontested pot (no flop) → no rake.
        assertThat(rakeService.computeRake(new BigDecimal("100.00"), false, 500, new BigDecimal("1.00")))
                .isEqualByComparingTo("0");
        // no cap (0) → full percentage.
        assertThat(rakeService.computeRake(new BigDecimal("100.00"), true, 500, BigDecimal.ZERO))
                .isEqualByComparingTo("5.00");
        // rounds down (never over-rakes): 5% of 3.33 = 0.1665 → 0.1665 (kept; never rounds up).
        assertThat(rakeService.computeRake(new BigDecimal("3.33"), true, 500, BigDecimal.ZERO))
                .isLessThanOrEqualTo(new BigDecimal("0.1665"));
    }

    @Test
    @DisplayName("collectRake records house revenue, is idempotent, and sums per table")
    void collectAndAccrue() {
        BigDecimal r1 = rakeService.collectRake(table, new BigDecimal("10.00"), true, "hand-1");
        BigDecimal r2 = rakeService.collectRake(table, new BigDecimal("40.00"), true, "hand-2");
        assertThat(r1).isEqualByComparingTo("0.50");
        assertThat(r2).isEqualByComparingTo("1.00"); // 5% of 40 = 2.00, capped at 1.00

        // Idempotent: re-settling hand-1 returns the same rake and adds no new entry.
        BigDecimal r1again = rakeService.collectRake(table, new BigDecimal("10.00"), true, "hand-1");
        assertThat(r1again).isEqualByComparingTo("0.50");

        assertThat(rakeRepository.findByCashTableId(table.getId())).hasSize(2);
        assertThat(rakeService.houseRevenue(table.getId())).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("an uncontested pot is not recorded and accrues nothing")
    void uncontestedNotRecorded() {
        assertThat(rakeService.collectRake(table, new BigDecimal("12.00"), false, "hand-walk"))
                .isEqualByComparingTo("0");
        assertThat(rakeRepository.findByCashTableId(table.getId())).isEmpty();
        assertThat(rakeService.houseRevenue(table.getId())).isEqualByComparingTo("0");
    }
}
