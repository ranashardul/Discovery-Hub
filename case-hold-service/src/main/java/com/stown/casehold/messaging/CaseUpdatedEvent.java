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
public class CaseUpdatedEvent {

    public static final String TYPE = "CASE_UPDATED";

    @Builder.Default
    private String eventType = TYPE;

    private UUID eventId;
    private String caseId;
    private String caseName;
    private String description;
    private String status;
    private String updatedBy;
    private Instant occurredAt;
}
