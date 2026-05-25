package com.truholdem.config;

/**
 * Bot decision strategy for {@link com.truholdem.service.PokerGameService#executeBotAction}.
 */
public enum BotMode {
    /** Monte Carlo + personality (default). */
    ADVANCED,
    /** Check when possible, otherwise call; fold only if cannot call. For load/pyramid tests. */
    PASSIVE
}
