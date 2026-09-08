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
public class HoldReleasedEvent {

    public static final String TYPE = "HOLD_RELEASED";

    @Builder.Default
    private String eventType = TYPE;

    private UUID eventId;
    private String holdId;
    private String caseId;
    private String status;
    private String releasedBy;
    private Instant releasedAt;
    private Instant occurredAt;
}
