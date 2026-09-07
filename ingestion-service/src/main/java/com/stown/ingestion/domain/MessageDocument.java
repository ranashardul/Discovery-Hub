package com.stown.ingestion.domain;
import org.springframework.data.mongodb.core.mapping.Document;
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

    @Id
    private String id;

    @Indexed(unique = true)
    private String deduplicationKey;

    private String communicationType;
    private String sender;
    private List<String> recipients;
    private String subject;
    private String body;
    private Instant messageTimestamp;
    private String threadId;

    private List<AttachmentMetadata> attachments;

    private Instant createdAt;
    private Instant retentionUntil;

    private int holdCount;
    private String dispositionStatus;
}