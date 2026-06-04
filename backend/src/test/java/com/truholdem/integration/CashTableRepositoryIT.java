package com.truholdem.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

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
import com.truholdem.repository.CashTableRepository;

@SpringBootTest
@ActiveProfiles("test")
@Import({ TestConfig.class, TestSecurityConfig.class })
@DisplayName("CashTable persistence + active-tables query")
class CashTableRepositoryIT {

    @Autowired
    private CashTableRepository repository;

    @Test
    @DisplayName("persists all fields and lists active tables ordered by small blind")
    void persistAndQuery() {
        repository.deleteAll();

        CashTable micro = repository.save(new CashTable("Micro USDT", CryptoAsset.USDT_ERC20,
                new BigDecimal("0.05"), new BigDecimal("0.10"),
                new BigDecimal("2.00"), new BigDecimal("20.00"), 6, 500, new BigDecimal("1.00")));
        CashTable low = repository.save(new CashTable("Low ETH", CryptoAsset.ETH,
                new BigDecimal("0.001"), new BigDecimal("0.002"),
                new BigDecimal("0.05"), new BigDecimal("0.50"), 9, 250, new BigDecimal("0.01")));
        CashTable closed = repository.save(new CashTable("Closed BTC", CryptoAsset.BTC,
                new BigDecimal("0.0001"), new BigDecimal("0.0002"),
                new BigDecimal("0.001"), new BigDecimal("0.01"), 6, 500, BigDecimal.ZERO));
        closed.setActive(false);
        repository.save(closed);

        CashTable reloaded = repository.findById(micro.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Micro USDT");
        assertThat(reloaded.getAsset()).isEqualTo(CryptoAsset.USDT_ERC20);
        assertThat(reloaded.getBigBlind()).isEqualByComparingTo("0.10");
        assertThat(reloaded.getMaxSeats()).isEqualTo(6);
        assertThat(reloaded.getRakeBasisPoints()).isEqualTo(500);
        assertThat(reloaded.getRakeCap()).isEqualByComparingTo("1.00");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.isActive()).isTrue();

        var active = repository.findByActiveTrueOrderBySmallBlindAsc();
        assertThat(active).extracting(CashTable::getName).containsExactly("Low ETH", "Micro USDT");
        assertThat(active).noneMatch(t -> t.getName().equals("Closed BTC"));
        assertThat(low.getId()).isNotNull();
    }
}
