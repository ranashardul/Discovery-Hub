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

/**
 * Registry of accepted ingestion requests. It provides the idempotency guard
 * for asynchronous processing and backs the status-lookup endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "ingestion_requests")
public class IngestionRequestDocument {

    @Id
    private String requestId;

    @Indexed(unique = true, name = "request_deduplicationKey_unique")
    private String deduplicationKey;

    @Indexed(unique = true, sparse = true, name = "request_externalMessageId_unique")
    private String externalMessageId;

    /**
     * Assigned once by the worker and never changed, so retries reuse the same
     * message identity.
     */
    private String messageId;

    private IngestionStatus status;

    private int attempts;
    private String lastError;

    private List<StagedAttachment> stagedAttachments;

    private Instant createdAt;
    private Instant updatedAt;
}
