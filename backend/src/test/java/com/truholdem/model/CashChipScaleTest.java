package com.truholdem.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CashChipScale: money <-> integer chip-unit mapping")
class CashChipScaleTest {

    @Test
    @DisplayName("chip unit makes the blinds whole chip counts (per blind decimals)")
    void chipUnitFromBlinds() {
        CashChipScale cents = CashChipScale.forBlinds(new BigDecimal("0.05"), new BigDecimal("0.10"));
        assertThat(cents.chipUnit()).isEqualByComparingTo("0.01");
        assertThat(cents.toChips(new BigDecimal("0.05"))).isEqualTo(5);
        assertThat(cents.toChips(new BigDecimal("0.10"))).isEqualTo(10);
        assertThat(cents.toChips(new BigDecimal("10.00"))).isEqualTo(1000);

        CashChipScale milli = CashChipScale.forBlinds(new BigDecimal("0.001"), new BigDecimal("0.002"));
        assertThat(milli.chipUnit()).isEqualByComparingTo("0.001");
        assertThat(milli.toChips(new BigDecimal("0.50"))).isEqualTo(500);

        CashChipScale sats = CashChipScale.forBlinds(new BigDecimal("0.0001"), new BigDecimal("0.0002"));
        assertThat(sats.chipUnit()).isEqualByComparingTo("0.0001");
        assertThat(sats.toChips(new BigDecimal("0.01"))).isEqualTo(100);
    }

    @Test
    @DisplayName("toChips floors sub-unit dust; toMoney is the exact inverse on whole chips")
    void roundTripAndDust() {
        CashChipScale scale = CashChipScale.forBlinds(new BigDecimal("0.05"), new BigDecimal("0.10"));

        // exact multiple: round-trips with no dust
        assertThat(scale.toChips(new BigDecimal("12.34"))).isEqualTo(1234);
        assertThat(scale.toMoney(1234)).isEqualByComparingTo("12.34");
        assertThat(scale.dust(new BigDecimal("12.34"))).isEqualByComparingTo("0");

        // sub-unit dust is floored off
        assertThat(scale.toChips(new BigDecimal("10.005"))).isEqualTo(1000);
        assertThat(scale.toMoney(1000)).isEqualByComparingTo("10.00");
        assertThat(scale.dust(new BigDecimal("10.005"))).isEqualByComparingTo("0.005");
    }

    @Test
    @DisplayName("forTable derives the scale from the table's blinds")
    void forTable() {
        CashTable table = new CashTable("T", CryptoAsset.USDT_TRC20,
                new BigDecimal("0.05"), new BigDecimal("0.10"),
                new BigDecimal("2.00"), new BigDecimal("20.00"), 6, 500, new BigDecimal("1.00"));
        CashChipScale scale = CashChipScale.forTable(table);
        assertThat(scale.chipUnit()).isEqualByComparingTo("0.01");
        assertThat(scale.toChips(table.getMaxBuyIn())).isEqualTo(2000);
    }

    @Test
    @DisplayName("rejects bad input and a chip count that would overflow the engine's int chips")
    void guards() {
        CashChipScale scale = CashChipScale.forBlinds(new BigDecimal("0.05"), new BigDecimal("0.10"));
        assertThatThrownBy(() -> scale.toChips(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CashChipScale.forBlinds(BigDecimal.ZERO, new BigDecimal("0.10")))
                .isInstanceOf(IllegalArgumentException.class);

        // 18-decimal blinds -> chip unit 1e-18; a 100-unit stack would be 1e20 chips, far beyond int.
        CashChipScale tiny = CashChipScale.forBlinds(
                new BigDecimal("0.000000000000000001"), new BigDecimal("0.000000000000000002"));
        assertThatThrownBy(() -> tiny.toChips(new BigDecimal("100")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chip range");
    }
}
