package com.stown.exportaudit.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Published by the API when an export is requested. The worker consumes it,
 * assembles the package and updates the job. Carried over Kafka so the export
 * is asynchronous and retried through the standard error handler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportRequestedEvent {

    private String exportId;
    private String caseId;
    private String holdId;
    private String scope;
    private String requestedBy;

    private String communicationType;
    private String sender;
    private String threadId;

    private Instant fromTimestamp;
    private Instant toTimestamp;

    /** Envelope id used for idempotent logging/audit. */
    private String eventId;
    private Instant requestedAt;
}
