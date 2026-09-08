package com.stown.casehold.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseCreatedEvent {

    public static final String TYPE = "CASE_CREATED";

    /** Carried in the JSON payload so consumers can route without headers. */
    @Builder.Default
    private String eventType = TYPE;

    private UUID eventId;
    private String caseId;
    private String caseName;
    private String status;
    private String createdBy;
    private Instant occurredAt;
}
