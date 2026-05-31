package com.truholdem.dto;

import com.truholdem.model.GameUpdateType;

/**
 * DTO representing a game update message for WebSocket broadcasts.
 * Uses GameUpdateType enum for type-safe update categorization.
 *
 * <p>{@code game} is typed as {@link Object} so the broadcaster can send a
 * sanitized JSON projection (with opponents' hole cards masked) instead of the
 * raw {@code Game} entity, while keeping the same wire shape.
 */
public record WebSocketGameUpdateMessage(
    GameUpdateType type,
    Object game,
    Object payload,
    String message
) {}
