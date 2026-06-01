package com.truholdem.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.model.GameEventLog;

/**
 * Read-model for one entry of the append-only domain-event log (Phase 4). The {@code payload} is
 * exposed as a parsed {@link JsonNode} so it serializes back to nested JSON rather than an escaped
 * string. Ordered by {@code seqNo} (publication order) when returned as a hand/game event stream.
 */
public record GameEventLogResponse(
        long seqNo,
        UUID gameId,
        int handNumber,
        String eventType,
        Instant occurredAt,
        JsonNode payload) {

    public static GameEventLogResponse from(GameEventLog e, ObjectMapper mapper) {
        JsonNode payloadNode;
        try {
            payloadNode = mapper.readTree(e.getPayload());
        } catch (JsonProcessingException ex) {
            // Should not happen (we wrote valid JSON), but never fail a read over a bad row.
            payloadNode = mapper.getNodeFactory().textNode(e.getPayload());
        }
        return new GameEventLogResponse(
                e.getSeqNo() == null ? 0L : e.getSeqNo(),
                e.getGameId(),
                e.getHandNumber(),
                e.getEventType(),
                e.getOccurredAt(),
                payloadNode);
    }
}
