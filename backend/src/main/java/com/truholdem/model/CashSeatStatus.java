package com.truholdem.model;

/**
 * Lifecycle of a {@link CashSeat} at a real-money ring table.
 *
 * <ul>
 *   <li>{@code ACTIVE} — seated and dealt into hands.</li>
 *   <li>{@code SITTING_OUT} — still holding the seat and stack but not dealt in (missed blinds, away).</li>
 *   <li>{@code LEAVING} — requested to stand up; dealt out and cashed out once the current hand finishes.</li>
 *   <li>{@code LEFT} — stood up and cashed out; the seat is free. Terminal.</li>
 * </ul>
 */
public enum CashSeatStatus {
    ACTIVE,
    SITTING_OUT,
    LEAVING,
    LEFT
}
