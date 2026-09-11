package com.stown.ingestion.messaging;

import com.stown.ingestion.domain.StagedAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Accepted ingestion request. Attachment binaries are not part of the event;
 * only staged object references travel through Kafka.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionRequestedEvent {

    private String requestId;
    private String externalMessageId;
    private String deduplicationKey;

    private String communicationType;
    private String sender;
    private List<String> recipients;
    private String subject;
    private String body;
    private Instant messageTimestamp;
    private String threadId;

    private List<StagedAttachment> attachments;

    /**
     * Per-message retention in minutes, or null to use the configured policy.
     * Validated by the API before publishing, and clamped again by the worker
     * because a replayed event can outlive the configuration that accepted it.
     */
    private Integer retentionMinutes;
}
