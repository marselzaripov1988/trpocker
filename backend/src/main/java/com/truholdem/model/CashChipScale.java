package com.truholdem.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The money &harr; chip-unit mapping for a cash (ring) table. The poker engine plays in whole integer chips,
 * while a cash table's stakes and stacks are real-money {@link java.math.BigDecimal} amounts (asset major
 * units). This value object fixes a per-table <b>chip unit</b> = 10<sup>-d</sup>, where {@code d} is the number
 * of decimal places needed to represent the blinds exactly, so the small/big blind are whole chip counts and a
 * stack quantises to chips with sub-unit "dust" floored off (and never charged / credited).
 *
 * <p>Example: SB 0.05 / BB 0.10 &rarr; chip unit 0.01 &rarr; SB = 5 chips, BB = 10 chips, a 10.00 stack = 1000
 * chips. Pure and immutable; no engine/JPA coupling (the engine-facing {@code Chips} is built by the caller).
 */
public final class CashChipScale {

    /** The engine plays in {@code int} chips, so a table's max chip count must fit a positive int. */
    private static final BigDecimal MAX_CHIPS = BigDecimal.valueOf(Integer.MAX_VALUE);

    private final int decimals;
    private final BigDecimal chipUnit;

    private CashChipScale(int decimals) {
        this.decimals = decimals;
        this.chipUnit = BigDecimal.ONE.movePointLeft(decimals);
    }

    /** The chip unit for a table whose blinds are {@code smallBlind} / {@code bigBlind} (both positive). */
    public static CashChipScale forBlinds(BigDecimal smallBlind, BigDecimal bigBlind) {
        Objects.requireNonNull(smallBlind, "smallBlind");
        Objects.requireNonNull(bigBlind, "bigBlind");
        if (smallBlind.signum() <= 0 || bigBlind.signum() <= 0) {
            throw new IllegalArgumentException("Blinds must be positive");
        }
        int decimals = Math.max(strippedScale(smallBlind), strippedScale(bigBlind));
        return new CashChipScale(decimals);
    }

    public static CashChipScale forTable(CashTable table) {
        return forBlinds(table.getSmallBlind(), table.getBigBlind());
    }

    /** Convert a money amount to whole chips, flooring any sub-unit dust. */
    public int toChips(BigDecimal money) {
        Objects.requireNonNull(money, "money");
        if (money.signum() < 0) {
            throw new IllegalArgumentException("Money cannot be negative: " + money);
        }
        BigDecimal chips = money.divide(chipUnit, 0, RoundingMode.DOWN);
        if (chips.compareTo(MAX_CHIPS) > 0) {
            throw new IllegalArgumentException(
                    money + " exceeds the chip range for chip unit " + chipUnit + " (max " + MAX_CHIPS + ")");
        }
        return chips.intValueExact();
    }

    /** Convert a whole chip count back to a money amount (exact at this scale). */
    public BigDecimal toMoney(int chips) {
        if (chips < 0) {
            throw new IllegalArgumentException("Chips cannot be negative: " + chips);
        }
        return chipUnit.multiply(BigDecimal.valueOf(chips)).setScale(decimals, RoundingMode.UNNECESSARY);
    }

    /** The sub-chip-unit remainder of {@code money} that does not map to a whole chip (lost on quantisation). */
    public BigDecimal dust(BigDecimal money) {
        return money.subtract(toMoney(toChips(money)));
    }

    public BigDecimal chipUnit() {
        return chipUnit;
    }

    public int decimals() {
        return decimals;
    }

    /** Decimal places needed to represent {@code value} exactly (trailing zeros and integers count as 0). */
    private static int strippedScale(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CashChipScale other && decimals == other.decimals;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(decimals);
    }

    @Override
    public String toString() {
        return "CashChipScale[chipUnit=" + chipUnit + "]";
    }
}
