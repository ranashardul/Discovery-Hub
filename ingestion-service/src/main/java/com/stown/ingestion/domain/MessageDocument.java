package com.stown.ingestion.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "messages")
public class MessageDocument {

    /**
     * Immutable message identity, also used as the Elasticsearch document ID
     * and as part of the S3 object key.
     */
    @Id
    private String id;

    @Indexed(unique = true, name = "deduplicationKey_unique")
    private String deduplicationKey;

    @Indexed(unique = true, sparse = true, name = "externalMessageId_unique")
    private String externalMessageId;

    private String requestId;

    @Indexed(name = "communicationType_idx")
    private String communicationType;

    @Indexed(name = "sender_idx")
    private String sender;

    private List<String> recipients;
    private String subject;
    private String body;

    @Indexed(name = "messageTimestamp_idx")
    private Instant messageTimestamp;

    @Indexed(name = "threadId_idx")
    private String threadId;

    private List<AttachmentMetadata> attachments;

    private Instant createdAt;
    private Instant retentionUntil;

    private int holdCount;
    private String dispositionStatus;

    /**
     * Outbox state for the {@code message.ingested} event. The event is written
     * with the message document itself, which keeps publication recoverable
     * without requiring a multi-document transaction.
     */
    @Indexed(name = "outboxStatus_idx")
    private OutboxStatus outboxStatus;

    private Instant outboxPublishedAt;
    private int outboxAttempts;
    private String outboxLastError;
}
