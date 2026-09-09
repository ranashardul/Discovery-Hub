package com.stown.ingestion.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a message has been removed from MongoDB and its attachment
 * objects purged.
 *
 * <p>Consumers, owned by their respective services:
 * <ul>
 *   <li>search-service deletes the Elasticsearch document, without which the
 *       subject and body remain searchable after disposition.</li>
 *   <li>the export/audit service records the disposition in the chain of
 *       custody.</li>
 * </ul>
 *
 * <p>Delivery is at-least-once, so consumers must be idempotent. Deleting an
 * already absent document is a no-op.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDisposedEvent {

    private UUID eventId;
    private String messageId;
    private String externalMessageId;
    private String communicationType;

    /** RETENTION when the scheduled job disposed it, MANUAL via the delete API. */
    private String reason;

    private int attachmentsPurged;
    private Instant retentionUntil;
    private Instant disposedAt;
}
