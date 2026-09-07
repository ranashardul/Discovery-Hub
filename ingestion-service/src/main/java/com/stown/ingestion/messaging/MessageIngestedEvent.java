package com.stown.ingestion.messaging;

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
public class MessageIngestedEvent {

    private UUID eventId;
    private String messageId;
    private String deduplicationKey;
    private Instant occurredAt;
}