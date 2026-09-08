package com.stown.casehold.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Emitted when a hold is created. Carries everything a consumer needs to
 * enforce preservation:
 *
 * <ul>
 *   <li>{@code scope = COMMUNICATION}: the explicit {@code communicationIds}.</li>
 *   <li>{@code scope = CRITERIA}: the rule fields ({@code criteriaParticipants},
 *       {@code criteriaCommunicationTypes}, {@code criteriaDateFrom},
 *       {@code criteriaDateTo}) which the message-owning service matches
 *       against its own store.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldCreatedEvent {

    public static final String TYPE = "HOLD_CREATED";

    @Builder.Default
    private String eventType = TYPE;

    private UUID eventId;
    private String holdId;
    private String caseId;
    private String name;
    private String status;
    private String scope;
    private List<String> communicationIds;
    private List<String> criteriaParticipants;
    private List<String> criteriaCommunicationTypes;
    private Instant criteriaDateFrom;
    private Instant criteriaDateTo;
    private String createdBy;
    private Instant occurredAt;
}
