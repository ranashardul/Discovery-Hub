package com.stown.search.domain;

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
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "messages")
public class MessageDocument {

    @Id
    private String id;

    private String deduplicationKey;
    private String externalMessageId;
    private String communicationType;
    private String sender;
    private List<String> recipients;
    private String subject;
    private String body;
    private Instant messageTimestamp;
    private String threadId;

    private List<AttachmentMetadata> attachments;

    private Instant createdAt;
}
