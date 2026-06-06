package com.truholdem.service.game;

/**
 * Thread-scoped guard that suppresses the live, timer-driven hand-lifecycle scheduling (the
 * {@code HAND_COMPLETED → RESULT_DELAY → NEXT_HAND} progression) while a PYRAMID round is being driven
 * synchronously by the simulation / admin "advance round" path.
 *
 * <p>Those drivers play each hand to completion and advance the bracket themselves, on the calling
 * thread, inside one transaction. The live lifecycle timer is meant for interactive cash / MTT play
 * (show the result for a few seconds, then auto-start the next hand); if it also fires for a
 * pyramid-driven hand it runs ~100 ms later on a separate scheduler thread in its own transaction and
 * races the driver — surfacing as {@code NoSuchElementException: Game not found} (the game is still in
 * the driver's uncommitted transaction) and {@code StaleStateException} on the tournament registration
 * the driver is concurrently updating. Suppressing it for the driver thread removes the race without
 * affecting interactive play (whose actions run on request threads where the guard is never set).
 */
public final class HandLifecycleScheduling {

    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private HandLifecycleScheduling() {
    }

    /** True when the current thread is driving pyramid hands and the live lifecycle timer must not fire. */
    public static boolean isSuppressed() {
        return SUPPRESSED.get();
    }

    /** Run {@code action} with live hand-lifecycle scheduling suppressed on this thread (restores prior state). */
    public static void runSuppressed(Runnable action) {
        Boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            SUPPRESSED.set(previous);
        }
    }
}
