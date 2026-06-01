package com.truholdem.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truholdem.domain.event.DomainEvent;
import com.truholdem.domain.event.GameStarted;
import com.truholdem.domain.event.HandCompleted;
import com.truholdem.dto.GameEventLogResponse;
import com.truholdem.model.GameEventLog;
import com.truholdem.repository.GameEventLogRepository;

/**
 * Append-only domain-event log (engine-migration Phase 4): persists every published domain event for
 * audit and event-sourced replay, and serves a hand's / game's ordered event stream back.
 *
 * <p>Each event is written in its OWN transaction ({@link Propagation#REQUIRES_NEW}) so an audit
 * failure can never roll back or block the game action that produced the event (the log is
 * best-effort, gameplay integrity first).
 */
@Service
public class GameEventLogService {

    private final GameEventLogRepository repository;
    private final ObjectMapper objectMapper;

    /** Tracks the in-progress hand number per game so events lacking one can be stamped. */
    private final ConcurrentHashMap<UUID, Integer> currentHandByGame = new ConcurrentHashMap<>();

    public GameEventLogService(GameEventLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(DomainEvent event) {
        UUID gameId = event.getGameId();
        int handNumber = resolveHandNumber(gameId, event);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize domain event " + event.getEventType() + " for game " + gameId, e);
        }

        repository.save(new GameEventLog(
                event.getEventId(), gameId, handNumber, event.getEventType(), event.getOccurredAt(), payload));
    }

    @Transactional(readOnly = true)
    public List<GameEventLogResponse> eventsForGame(UUID gameId) {
        return repository.findByGameIdOrderBySeqNoAsc(gameId).stream()
                .map(e -> GameEventLogResponse.from(e, objectMapper))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GameEventLogResponse> eventsForHand(UUID gameId, int handNumber) {
        return repository.findByGameIdAndHandNumberOrderBySeqNoAsc(gameId, handNumber).stream()
                .map(e -> GameEventLogResponse.from(e, objectMapper))
                .toList();
    }

    private int resolveHandNumber(UUID gameId, DomainEvent event) {
        if (event instanceof GameStarted started) {
            currentHandByGame.put(gameId, started.getHandNumber());
            return started.getHandNumber();
        }
        if (event instanceof HandCompleted completed) {
            return completed.getHandNumber();
        }
        return currentHandByGame.getOrDefault(gameId, 0);
    }
}
