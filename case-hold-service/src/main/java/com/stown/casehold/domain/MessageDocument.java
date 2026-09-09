package com.stown.casehold.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Read-only projection of the {@code messages} collection owned by
 * ingestion-service. This service never writes to it.
 *
 * <p>Used to resolve a stored {@code communication_id} into the metadata a
 * reviewer needs (who sent it, what it was about, when) so the API can return
 * something meaningful instead of a bare identifier.
 *
 * <p>The message {@code body} is deliberately absent: a case references
 * communications, it does not hold their content. Export packaging is the
 * export-audit service's job.
 *
 * <p>{@code holdIds}, {@code holdCount} and {@code dispositionStatus} are
 * projected onto each message by ingestion-service in response to the
 * {@code case-hold.events} this service publishes. Reading them back lets a
 * caller confirm that a hold was actually enforced on the message data, not
 * merely recorded here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "messages")
public class MessageDocument {

    @Id
    private String id;

    private String communicationType;
    private String sender;
    private List<String> recipients;
    private String subject;
    private Instant messageTimestamp;
    private String threadId;

    private List<AttachmentMetadata> attachments;

    private Instant createdAt;
    private Instant retentionUntil;

    private List<String> holdIds;
    private int holdCount;
    private String dispositionStatus;
}
