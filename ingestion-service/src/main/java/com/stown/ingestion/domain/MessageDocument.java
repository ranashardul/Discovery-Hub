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

    /**
     * When retention expires. Computed at ingestion from the policy for this
     * communication type and stored, so the applied policy stays auditable
     * per message rather than shifting when configuration changes.
     */
    @Indexed(name = "retentionUntil_idx")
    private Instant retentionUntil;

    /**
     * Identifiers of every legal hold currently covering this message,
     * projected from {@code case-hold.events}.
     *
     * <p>A set rather than a counter: hold events are delivered at least once,
     * so incrementing would double-count on redelivery, and a release event
     * carries no message list, so the membership has to be recorded here.
     */
    @Indexed(name = "holdIds_idx")
    private List<String> holdIds;

    /**
     * Number of active holds, derived from {@link #holdIds}.
     *
     * <p>Read directly by search-service, which keeps its own copy of this
     * model with no compile-time link. The name and type must not change.
     */
    private int holdCount;

    /**
     * {@code ACTIVE} or {@code ON_HOLD}. Also read and indexed by
     * search-service as a keyword, so this stays a plain String.
     */
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
