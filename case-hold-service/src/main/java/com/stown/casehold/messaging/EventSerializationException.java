package com.stown.casehold.messaging;

import tools.jackson.core.JacksonException;

/**
 * Raised when a domain event cannot be serialised for the outbox. Throwing
 * (rather than swallowing) rolls back the surrounding transaction, so a case
 * or hold is never persisted without its event.
 */
public class EventSerializationException extends RuntimeException {

    public EventSerializationException(String eventType, String aggregateId, JacksonException cause) {
        super("Failed to serialise " + eventType + " event for aggregate " + aggregateId, cause);
    }
}
