package com.truholdem.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit/event-sourcing log of domain events (engine-migration Phase 4).
 *
 * <p>One row per published {@link com.truholdem.domain.event.DomainEvent}. Rows are never updated or
 * deleted on the live path. The primary key {@code seqNo} is a DB identity assigned in insert =
 * publication order, so {@code ORDER BY seqNo} replays events in order; {@code (gameId, handNumber)}
 * narrows to one hand. {@code eventId} is the originating event's id (unique) for idempotency, and
 * {@code payload} is the JSON serialization of the event.
 */
@Entity
@Table(name = "game_event_log", indexes = {
    @Index(name = "idx_game_event_log_game", columnList = "gameId"),
    @Index(name = "idx_game_event_log_game_hand", columnList = "gameId, handNumber, seqNo"),
    @Index(name = "idx_game_event_log_event_id", columnList = "eventId", unique = true)
})
public class GameEventLog {

    /** Global monotonic ordering + primary key, assigned by the DB in insert (publication) order. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seqNo;

    /** Originating event id — unique idempotency key. */
    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Column(nullable = false)
    private UUID gameId;

    @Column(nullable = false)
    private int handNumber;

    @Column(nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false)
    private Instant occurredAt;

    @Lob
    @Column(nullable = false)
    private String payload;

    public GameEventLog() {
    }

    public GameEventLog(UUID eventId, UUID gameId, int handNumber, String eventType, Instant occurredAt, String payload) {
        this.eventId = eventId;
        this.gameId = gameId;
        this.handNumber = handNumber;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.payload = payload;
    }

    public Long getSeqNo() { return seqNo; }
    public void setSeqNo(Long seqNo) { this.seqNo = seqNo; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public UUID getGameId() { return gameId; }
    public void setGameId(UUID gameId) { this.gameId = gameId; }

    public int getHandNumber() { return handNumber; }
    public void setHandNumber(int handNumber) { this.handNumber = handNumber; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
}
