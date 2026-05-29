package com.truholdem.config;

/**
 * Selects which poker hand engine {@link com.truholdem.service.PokerGameService} uses.
 * Default {@link #LEGACY} keeps current production behaviour until explicitly enabled.
 */
public enum GameEngine {
    LEGACY,
    AGGREGATE
}
