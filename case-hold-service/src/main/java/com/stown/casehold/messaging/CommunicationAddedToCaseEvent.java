package com.stown.casehold.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Emitted once per communication added to a case so consumers can react to a
 * single fact at a time. Re-adding an already-associated communication is
 * idempotent and does not emit an event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommunicationAddedToCaseEvent {

    public static final String TYPE = "COMMUNICATION_ADDED_TO_CASE";

    @Builder.Default
    private String eventType = TYPE;

    private UUID eventId;
    private String caseId;
    private String communicationId;
    private String communicationType;
    private String addedBy;
    private Instant occurredAt;
}
