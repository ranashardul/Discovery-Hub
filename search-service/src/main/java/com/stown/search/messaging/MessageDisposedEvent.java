package com.stown.search.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side copy of the {@code message.disposed} event published by
 * ingestion-service once a message has been deleted from MongoDB and its
 * attachments purged from object storage.
 *
 * <p>Only {@code messageId} is required to act on it; the remaining fields are
 * carried for logging and audit. Unknown properties are ignored so the
 * producer can add fields without breaking this consumer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageDisposedEvent {

    private UUID eventId;
    private String messageId;
    private String externalMessageId;
    private String communicationType;

    /** RETENTION when a scheduled sweep disposed it, MANUAL via the delete API. */
    private String reason;

    private int attachmentsPurged;
    private Instant retentionUntil;
    private Instant disposedAt;
}
