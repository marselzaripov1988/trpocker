package com.truholdem.application.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.truholdem.config.AppProperties;
import com.truholdem.domain.event.DomainEvent;
import com.truholdem.service.GameEventLogService;

/**
 * Phase 4: appends every published domain event to the Postgres event log. A single base-type
 * handler catches all subtypes. Synchronous (preserves publication order) but best-effort — an audit
 * failure is logged and swallowed so it can never break the game action ({@link GameEventLogService}
 * writes in its own transaction, so swallowing here does not mark the game transaction rollback-only).
 */
@Component
public class GameEventLogListener {

    private static final Logger log = LoggerFactory.getLogger(GameEventLogListener.class);

    private final GameEventLogService eventLogService;
    private final AppProperties appProperties;

    public GameEventLogListener(GameEventLogService eventLogService, AppProperties appProperties) {
        this.eventLogService = eventLogService;
        this.appProperties = appProperties;
    }

    @EventListener
    public void onDomainEvent(DomainEvent event) {
        if (!appProperties.getGame().isEventLogEnabled()) {
            return;
        }
        try {
            eventLogService.record(event);
        } catch (Exception e) {
            log.error("Failed to append domain event {} for game {} to the event log",
                    event.getEventType(), event.getGameId(), e);
        }
    }
}
