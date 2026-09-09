package com.stown.ingestion.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Local, permissive view of an event on {@code case-hold.events}, which is
 * owned by the case-hold service.
 *
 * <p>That topic carries every Case and Hold domain event on one partition set
 * and the payload arrives as plain JSON text (the producer uses a
 * {@code StringSerializer} over its outbox rows), so there is no type header
 * to bind against. Routing is on {@link #eventType}, and unknown properties
 * are ignored so the producer can add fields without breaking ingestion.
 *
 * <p>This is a deliberate copy rather than a shared class: the two services
 * are independently deployable and must not share a compile-time dependency.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseHoldEvent {

    public static final String HOLD_CREATED = "HOLD_CREATED";
    public static final String HOLD_RELEASED = "HOLD_RELEASED";

    public static final String SCOPE_COMMUNICATION = "COMMUNICATION";
    public static final String SCOPE_CRITERIA = "CRITERIA";

    private String eventType;
    private String eventId;
    private String holdId;
    private String caseId;
    private String status;

    /** COMMUNICATION or CRITERIA. */
    private String scope;

    /** Explicit message ids, for COMMUNICATION scope. */
    private List<String> communicationIds;

    /** Rule fields, for CRITERIA scope; matched against our own store. */
    private List<String> criteriaParticipants;
    private List<String> criteriaCommunicationTypes;
    private Instant criteriaDateFrom;
    private Instant criteriaDateTo;

    private Instant occurredAt;

    public boolean isHoldCreated() {
        return HOLD_CREATED.equalsIgnoreCase(eventType);
    }

    public boolean isHoldReleased() {
        return HOLD_RELEASED.equalsIgnoreCase(eventType);
    }

    public boolean isCriteriaScope() {
        return SCOPE_CRITERIA.equalsIgnoreCase(scope);
    }
}
